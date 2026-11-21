"""OAuth token bridge to the app's AES-GCM Android Keystore storage."""

SERVICE = "google-colab-cli"
USERNAME = "oauth_token"


def get_token():
    from java import jclass

    store = jclass("dev.trung.colabterm.AndroidTokenStore")
    return store.getPassword(SERVICE, USERNAME)


def set_token(token_json):
    from java import jclass

    store = jclass("dev.trung.colabterm.AndroidTokenStore")
    store.setPassword(SERVICE, USERNAME, token_json)


def delete_token():
    from java import jclass

    store = jclass("dev.trung.colabterm.AndroidTokenStore")
    return bool(store.deletePassword(SERVICE, USERNAME))
