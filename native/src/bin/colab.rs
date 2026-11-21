use std::env;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::process;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const TOKEN_BYTES: usize = 64;
const MAX_ARGUMENT_COUNT: usize = 256;
const MAX_FRAME_BYTES: usize = 4 * 1024 * 1024;
const INPUT_CHUNK_BYTES: usize = 16 * 1024;
const RESIZE_POLL_INTERVAL: Duration = Duration::from_millis(150);
const DEFAULT_TERMINAL_COLS: u16 = 80;
const DEFAULT_TERMINAL_ROWS: u16 = 24;

fn main() {
    match run() {
        Ok(code) => process::exit(code.clamp(0, 255)),
        Err(error) => {
            eprintln!("colab: bridge error: {error}");
            process::exit(127);
        }
    }
}

fn run() -> io::Result<i32> {
    let port = env::var("COLABTERM_BRIDGE_PORT")
        .map_err(|_| invalid_input("COLABTERM_BRIDGE_PORT is not set"))?
        .parse::<u16>()
        .map_err(|_| invalid_input("invalid bridge port"))?;
    let token = env::var("COLABTERM_BRIDGE_TOKEN")
        .map_err(|_| invalid_input("COLABTERM_BRIDGE_TOKEN is not set"))?;
    let args: Vec<String> = env::args().skip(1).collect();
    let interactive = is_interactive_command(&args);
    let mut stream = TcpStream::connect(("127.0.0.1", port))?;
    stream.set_nodelay(true)?;

    if interactive {
        let (cols, rows) = terminal_size(libc::STDIN_FILENO);
        let request = encode_interactive_request(&token, &args, cols, rows)?;
        let raw_terminal = RawTerminal::enter(libc::STDIN_FILENO)?;
        stream.write_all(&request)?;
        stream.flush()?;

        let writer = Arc::new(Mutex::new(stream.try_clone()?));
        let stopped = Arc::new(AtomicBool::new(false));
        spawn_interactive_input(Arc::clone(&writer), Arc::clone(&stopped));
        spawn_resize_monitor(Arc::clone(&writer), Arc::clone(&stopped), (cols, rows));

        let result = relay_frames(stream, io::stdout().lock());
        stopped.store(true, Ordering::Release);
        drop(raw_terminal);
        result
    } else {
        let request = encode_request(&token, &args)?;
        stream.write_all(&request)?;
        stream.flush()?;

        let mut input_stream = stream.try_clone()?;
        let input_thread = thread::spawn(move || {
            let stdin = io::stdin();
            let mut input = stdin.lock();
            let _ = io::copy(&mut input, &mut input_stream);
            let _ = input_stream.shutdown(Shutdown::Write);
        });

        let code = relay_frames(stream, io::stdout().lock())?;
        drop(input_thread);
        Ok(code)
    }
}

fn is_interactive_command(args: &[String]) -> bool {
    matches!(args.first().map(String::as_str), Some("console" | "ssh"))
}

fn spawn_interactive_input(writer: Arc<Mutex<TcpStream>>, stopped: Arc<AtomicBool>) {
    thread::spawn(move || {
        let stdin = io::stdin();
        let mut input = stdin.lock();
        let mut buffer = [0u8; INPUT_CHUNK_BYTES];
        loop {
            match input.read(&mut buffer) {
                Ok(0) => {
                    stopped.store(true, Ordering::Release);
                    if let Ok(mut stream) = writer.lock() {
                        let _ = write_eof_frame(&mut *stream);
                        let _ = stream.shutdown(Shutdown::Write);
                    }
                    break;
                }
                Ok(length) => {
                    let result = writer
                        .lock()
                        .map_err(|_| invalid_data("bridge input writer lock poisoned"))
                        .and_then(|mut stream| write_input_frame(&mut *stream, &buffer[..length]));
                    if result.is_err() {
                        stopped.store(true, Ordering::Release);
                        break;
                    }
                }
                Err(_) => {
                    stopped.store(true, Ordering::Release);
                    if let Ok(mut stream) = writer.lock() {
                        let _ = write_eof_frame(&mut *stream);
                        let _ = stream.shutdown(Shutdown::Write);
                    }
                    break;
                }
            }
        }
    });
}

fn spawn_resize_monitor(
    writer: Arc<Mutex<TcpStream>>,
    stopped: Arc<AtomicBool>,
    initial_size: (u16, u16),
) {
    thread::spawn(move || {
        let mut last_size = initial_size;
        while !stopped.load(Ordering::Acquire) {
            thread::sleep(RESIZE_POLL_INTERVAL);
            let size = terminal_size(libc::STDIN_FILENO);
            if size == last_size {
                continue;
            }
            let result = writer
                .lock()
                .map_err(|_| invalid_data("bridge resize writer lock poisoned"))
                .and_then(|mut stream| write_resize_frame(&mut *stream, size.0, size.1));
            if result.is_err() {
                break;
            }
            last_size = size;
        }
    });
}

fn terminal_size(fd: libc::c_int) -> (u16, u16) {
    let mut size = unsafe { std::mem::zeroed::<libc::winsize>() };
    let result = unsafe { libc::ioctl(fd, libc::TIOCGWINSZ as _, &mut size) };
    if result == 0 && size.ws_col > 0 && size.ws_row > 0 {
        (size.ws_col, size.ws_row)
    } else {
        (DEFAULT_TERMINAL_COLS, DEFAULT_TERMINAL_ROWS)
    }
}

struct RawTerminal {
    fd: libc::c_int,
    original: Option<libc::termios>,
}

impl RawTerminal {
    fn enter(fd: libc::c_int) -> io::Result<Self> {
        if unsafe { libc::isatty(fd) } != 1 {
            return Ok(Self { fd, original: None });
        }

        let mut original = unsafe { std::mem::zeroed::<libc::termios>() };
        if unsafe { libc::tcgetattr(fd, &mut original) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let mut raw = original;
        raw.c_iflag &= !(libc::BRKINT | libc::ICRNL | libc::INPCK | libc::ISTRIP | libc::IXON);
        raw.c_oflag &= !libc::OPOST;
        raw.c_cflag |= libc::CS8;
        raw.c_lflag &= !(libc::ECHO | libc::ICANON | libc::IEXTEN | libc::ISIG);
        raw.c_cc[libc::VMIN] = 1;
        raw.c_cc[libc::VTIME] = 0;
        if unsafe { libc::tcsetattr(fd, libc::TCSANOW, &raw) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(Self {
            fd,
            original: Some(original),
        })
    }
}

impl Drop for RawTerminal {
    fn drop(&mut self) {
        if let Some(original) = self.original.as_ref() {
            unsafe {
                libc::tcsetattr(self.fd, libc::TCSANOW, original);
            }
        }
    }
}

fn encode_request(token: &str, args: &[String]) -> io::Result<Vec<u8>> {
    encode_request_with_magic(b"CTC1", token, args)
}

fn encode_interactive_request(
    token: &str,
    args: &[String],
    cols: u16,
    rows: u16,
) -> io::Result<Vec<u8>> {
    if !is_interactive_command(args) {
        return Err(invalid_input("interactive request requires console or ssh"));
    }
    let mut frame = encode_request_with_magic(b"CTC2", token, args)?;
    frame.extend_from_slice(&cols.to_be_bytes());
    frame.extend_from_slice(&rows.to_be_bytes());
    Ok(frame)
}

fn encode_request_with_magic(magic: &[u8; 4], token: &str, args: &[String]) -> io::Result<Vec<u8>> {
    if token.len() != TOKEN_BYTES || !token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(invalid_input("bridge token must be 64 hexadecimal bytes"));
    }
    if args.len() > MAX_ARGUMENT_COUNT {
        return Err(invalid_input("too many command arguments"));
    }

    let mut frame = Vec::new();
    frame.extend_from_slice(magic);
    frame.extend_from_slice(token.as_bytes());
    frame.extend_from_slice(&(args.len() as u32).to_be_bytes());
    for arg in args {
        let bytes = arg.as_bytes();
        if bytes.len() > MAX_FRAME_BYTES {
            return Err(invalid_input("command argument is too large"));
        }
        frame.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
        frame.extend_from_slice(bytes);
    }
    Ok(frame)
}

fn write_input_frame<W: Write>(writer: &mut W, bytes: &[u8]) -> io::Result<()> {
    if bytes.len() > MAX_FRAME_BYTES {
        return Err(invalid_input("bridge input frame is too large"));
    }
    writer.write_all(b"I")?;
    writer.write_all(&(bytes.len() as u32).to_be_bytes())?;
    writer.write_all(bytes)?;
    writer.flush()
}

fn write_resize_frame<W: Write>(writer: &mut W, cols: u16, rows: u16) -> io::Result<()> {
    if cols == 0 || rows == 0 {
        return Err(invalid_input("terminal size must be greater than zero"));
    }
    writer.write_all(b"R")?;
    writer.write_all(&cols.to_be_bytes())?;
    writer.write_all(&rows.to_be_bytes())?;
    writer.flush()
}

fn write_eof_frame<W: Write>(writer: &mut W) -> io::Result<()> {
    writer.write_all(b"C")?;
    writer.flush()
}

fn relay_frames<R: Read, W: Write>(mut reader: R, mut output: W) -> io::Result<i32> {
    loop {
        let mut frame_type = [0u8; 1];
        match reader.read(&mut frame_type)? {
            0 => {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "bridge closed without exit status",
                ))
            }
            1 => {}
            _ => unreachable!(),
        }

        match frame_type[0] {
            b'O' => {
                let length = read_u32(&mut reader)? as usize;
                if length > MAX_FRAME_BYTES {
                    return Err(invalid_data("bridge output frame is too large"));
                }
                let mut payload = vec![0; length];
                reader.read_exact(&mut payload)?;
                output.write_all(&payload)?;
                output.flush()?;
            }
            b'E' => {
                let mut bytes = [0u8; 4];
                reader.read_exact(&mut bytes)?;
                return Ok(i32::from_be_bytes(bytes));
            }
            _ => return Err(invalid_data("unknown bridge output frame")),
        }
    }
}

fn read_u32<R: Read>(reader: &mut R) -> io::Result<u32> {
    let mut bytes = [0u8; 4];
    reader.read_exact(&mut bytes)?;
    Ok(u32::from_be_bytes(bytes))
}

fn invalid_input(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn invalid_data(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

#[cfg(test)]
mod tests {
    use super::{
        encode_interactive_request, encode_request, relay_frames, write_input_frame,
        write_resize_frame,
    };
    use std::io::Cursor;

    #[test]
    fn request_frame_preserves_argument_boundaries() {
        let token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        let args = vec!["exec".to_string(), "file with spaces.py".to_string()];
        let frame = encode_request(token, &args).unwrap();

        assert_eq!(&frame[0..4], b"CTC1");
        assert_eq!(&frame[4..68], token.as_bytes());
        assert_eq!(&frame[68..72], &2u32.to_be_bytes());
        assert_eq!(&frame[72..76], &4u32.to_be_bytes());
        assert_eq!(&frame[76..80], b"exec");
        assert_eq!(&frame[80..84], &19u32.to_be_bytes());
        assert_eq!(&frame[84..], b"file with spaces.py");
    }

    #[test]
    fn request_rejects_malformed_tokens() {
        assert!(encode_request("short", &[]).is_err());
    }

    #[test]
    fn interactive_request_uses_ctc2_and_includes_terminal_size() {
        let token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        let args = vec![
            "console".to_string(),
            "-s".to_string(),
            "t4-lab".to_string(),
        ];
        let frame = encode_interactive_request(token, &args, 120, 40).unwrap();

        assert_eq!(&frame[0..4], b"CTC2");
        assert_eq!(&frame[4..68], token.as_bytes());
        assert_eq!(&frame[68..72], &3u32.to_be_bytes());
        assert_eq!(&frame[frame.len() - 4..], &[0, 120, 0, 40]);
    }

    #[test]
    fn interactive_input_and_resize_frames_preserve_data() {
        let mut frame = Vec::new();
        write_input_frame(&mut frame, &[0, 0x1b, 0x7f, 0xff]).unwrap();
        write_resize_frame(&mut frame, 100, 30).unwrap();

        assert_eq!(&frame[0..5], &[b'I', 0, 0, 0, 4]);
        assert_eq!(&frame[5..9], &[0, 0x1b, 0x7f, 0xff]);
        assert_eq!(&frame[9..14], &[b'R', 0, 100, 0, 30]);
    }

    #[test]
    fn output_frames_are_forwarded_and_exit_status_is_returned() {
        let mut response = Vec::new();
        response.push(b'O');
        response.extend_from_slice(&3u32.to_be_bytes());
        response.extend_from_slice(b"ok\n");
        response.push(b'E');
        response.extend_from_slice(&7i32.to_be_bytes());

        let mut output = Vec::new();
        let code = relay_frames(Cursor::new(response), &mut output).unwrap();
        assert_eq!(output, b"ok\n");
        assert_eq!(code, 7);
    }

    #[test]
    fn rejects_malformed_output_frames() {
        let mut output = Vec::new();
        let result = relay_frames(Cursor::new(vec![b'X']), &mut output);
        assert!(result.is_err());
    }
}
