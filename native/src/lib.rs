use jni::{
    objects::{JClass, JObjectArray, JString},
    sys::jstring,
    JNIEnv,
};
use std::fmt;

#[derive(Debug, PartialEq, Eq)]
pub enum CommandError {
    Empty,
    NotColab,
    ShellSyntax,
    MissingOptionValue(String),
    UnsupportedCommand(String),
    InteractiveUnsupported(String),
}

impl fmt::Display for CommandError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => write!(f, "Enter a colab command to continue."),
            Self::NotColab => write!(f, "Only commands beginning with `colab` are supported."),
            Self::ShellSyntax => write!(
                f,
                "Chained commands and pipes are not supported in this terminal MVP."
            ),
            Self::MissingOptionValue(option) => write!(f, "Missing value for option {option}."),
            Self::UnsupportedCommand(command) => {
                write!(f, "Command `colab {command}` is not supported yet.")
            }
            Self::InteractiveUnsupported(command) => {
                write!(
                    f,
                    "`colab {command}` requires an interactive PTY/SSH and is not supported in the MVP."
                )
            }
        }
    }
}

const COMMANDS: &[&str] = &[
    "help",
    "version",
    "new",
    "sessions",
    "status",
    "restart-kernel",
    "stop",
    "run",
    "exec",
    "ls",
    "upload",
    "download",
    "rm",
    "edit",
    "install",
    "log",
    "drivemount",
    "auth",
    "usage",
    "console",
    "ssh",
];

const INTERACTIVE_COMMANDS: &[&str] = &["repl"];

/// Parse a user-entered `colab ...` line into argv, without invoking a shell.
pub fn parse_colab_command(input: &str) -> Result<Vec<String>, CommandError> {
    if input.trim().is_empty() {
        return Err(CommandError::Empty);
    }
    if input.contains('\n') || input.contains('\r') {
        return Err(CommandError::ShellSyntax);
    }

    let tokens = shell_words::split(input).map_err(|_| CommandError::ShellSyntax)?;
    parse_colab_args(&tokens)
}

/// Validate argv received from the shell bridge without reparsing a command string.
pub fn parse_colab_args(tokens: &[String]) -> Result<Vec<String>, CommandError> {
    if tokens.is_empty() {
        return Err(CommandError::Empty);
    }
    if tokens
        .iter()
        .any(|token| token.contains('\n') || token.contains('\r'))
    {
        return Err(CommandError::ShellSyntax);
    }
    if tokens[0] != "colab" {
        return Err(CommandError::NotColab);
    }
    if tokens.iter().any(|token| {
        [";", "&&", "||", "|", ">", "<", "`"]
            .iter()
            .any(|operator| token == operator || token.contains(operator))
    }) {
        return Err(CommandError::ShellSyntax);
    }

    let args = &tokens[1..];
    let command_index = find_command_index(args)?;
    let Some(index) = command_index else {
        return Ok(if args.is_empty() {
            vec!["--help".into()]
        } else {
            args.to_vec()
        });
    };

    let command = args[index].as_str();
    if INTERACTIVE_COMMANDS.contains(&command) {
        return Err(CommandError::InteractiveUnsupported(command.to_string()));
    }
    if !COMMANDS.contains(&command) {
        return Err(CommandError::UnsupportedCommand(command.to_string()));
    }

    Ok(args.to_vec())
}

fn find_command_index(args: &[String]) -> Result<Option<usize>, CommandError> {
    let mut i = 0;
    while i < args.len() {
        let token = args[i].as_str();
        if token == "--help" || token == "-h" || token == "--logtostderr" {
            i += 1;
            continue;
        }
        if token == "--auth"
            || token == "--config"
            || token == "--client-oauth-config"
            || token == "-c"
        {
            let value_index = i + 1;
            let Some(value) = args.get(value_index) else {
                return Err(CommandError::MissingOptionValue(token.to_string()));
            };
            if token == "--auth" && value != "oauth2" && value != "adc" {
                return Err(CommandError::UnsupportedCommand(format!(
                    "auth strategy {}",
                    value
                )));
            }
            i += 2;
            continue;
        }
        if let Some(value) = token.strip_prefix("--auth=") {
            if value != "oauth2" && value != "adc" {
                return Err(CommandError::UnsupportedCommand(format!(
                    "auth strategy {value}"
                )));
            }
            i += 1;
            continue;
        }
        if token.starts_with("--config=") || token.starts_with("--client-oauth-config=") {
            i += 1;
            continue;
        }
        return Ok(Some(i));
    }
    Ok(None)
}

/// JNI entry point: returns newline-delimited argv, or a user-facing error prefixed by !.
#[no_mangle]
pub extern "system" fn Java_dev_trung_colabterm_MainActivity_nativeNormalizeCommand(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let text = match env.get_string(&input) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return std::ptr::null_mut(),
    };
    let output = match parse_colab_command(&text) {
        Ok(args) => args.join("\n"),
        Err(error) => format!("!{error}"),
    };
    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// JNI entry point for shell argv: returns newline-delimited CLI args or an error prefixed by !.
#[no_mangle]
pub extern "system" fn Java_dev_trung_colabterm_MainActivity_nativeNormalizeColabArguments(
    mut env: JNIEnv,
    _class: JClass,
    input: JObjectArray,
) -> jstring {
    let length = match env.get_array_length(&input) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut tokens = Vec::with_capacity(length as usize + 1);
    tokens.push("colab".to_string());
    for index in 0..length {
        let value = match env.get_object_array_element(&input, index) {
            Ok(value) => JString::from(value),
            Err(_) => return std::ptr::null_mut(),
        };
        let text = match env.get_string(&value) {
            Ok(text) => text.to_string_lossy().into_owned(),
            Err(_) => return std::ptr::null_mut(),
        };
        tokens.push(text);
    }
    let output = match parse_colab_args(&tokens) {
        Ok(args) => args.join("\n"),
        Err(error) => format!("!{error}"),
    };
    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::{parse_colab_args, parse_colab_command, CommandError};

    #[test]
    fn accepts_colab_subcommand() {
        assert_eq!(
            parse_colab_command("colab sessions").unwrap(),
            vec!["sessions"]
        );
    }

    #[test]
    fn preserves_quoted_paths_as_single_arguments() {
        assert_eq!(
            parse_colab_command("colab upload \"/sdcard/model weights.zip\" /content/model.zip")
                .unwrap(),
            vec!["upload", "/sdcard/model weights.zip", "/content/model.zip"]
        );
    }

    #[test]
    fn permits_supported_global_auth_options() {
        assert_eq!(
            parse_colab_command("colab --auth oauth2 sessions").unwrap(),
            vec!["--auth", "oauth2", "sessions"]
        );
    }

    #[test]
    fn validates_shell_argv_without_losing_argument_boundaries() {
        let argv = vec![
            "colab".to_string(),
            "exec".to_string(),
            "--file".to_string(),
            "/app/files/my script.py".to_string(),
        ];
        assert_eq!(
            parse_colab_args(&argv).unwrap(),
            vec!["exec", "--file", "/app/files/my script.py"]
        );
    }

    #[test]
    fn rejects_shell_operators_from_colab_argv() {
        let argv = vec![
            "colab".to_string(),
            "sessions".to_string(),
            "&&".to_string(),
        ];
        assert_eq!(parse_colab_args(&argv), Err(CommandError::ShellSyntax));
    }

    #[test]
    fn rejects_non_colab_shell_commands() {
        assert_eq!(
            parse_colab_command("echo hello"),
            Err(CommandError::NotColab)
        );
    }

    #[test]
    fn rejects_unknown_colab_commands() {
        assert_eq!(
            parse_colab_command("colab shell").unwrap_err(),
            CommandError::UnsupportedCommand("shell".to_string())
        );
    }

    #[test]
    fn rejects_shell_chaining_tokens() {
        assert_eq!(
            parse_colab_command("colab sessions && rm -rf /").unwrap_err(),
            CommandError::ShellSyntax
        );
    }

    #[test]
    fn rejects_empty_input() {
        assert_eq!(parse_colab_command("  ").unwrap_err(), CommandError::Empty);
    }

    #[test]
    fn accepts_console_and_ssh_interactive_commands() {
        assert_eq!(
            parse_colab_command("colab console -s t4-lab").unwrap(),
            vec!["console", "-s", "t4-lab"]
        );
        assert_eq!(
            parse_colab_command("colab ssh --session t4-lab").unwrap(),
            vec!["ssh", "--session", "t4-lab"]
        );
    }

    #[test]
    fn keeps_python_repl_unsupported_without_a_pty_implementation() {
        assert_eq!(
            parse_colab_command("colab repl").unwrap_err(),
            CommandError::InteractiveUnsupported("repl".to_string())
        );
    }
}
