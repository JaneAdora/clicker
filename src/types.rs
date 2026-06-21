// /home/jane/projects/clicker/src/types.rs

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RemoteKey {
    Up, Down, Left, Right, Select,
    Home, Back, Menu, Power,
    VolUp, VolDown, Mute,
    PlayPause, Next, Prev, Rewind, FastForward, Stop,
    ChannelUp, ChannelDown,
    Settings, Input, Assist,
}

impl RemoteKey {
    pub fn keycode(self) -> i32 {
        match self {
            RemoteKey::Up => 19,
            RemoteKey::Down => 20,
            RemoteKey::Left => 21,
            RemoteKey::Right => 22,
            RemoteKey::Select => 23,
            RemoteKey::Home => 3,
            RemoteKey::Back => 4,
            RemoteKey::Menu => 82,
            RemoteKey::Power => 26,
            RemoteKey::VolUp => 24,
            RemoteKey::VolDown => 25,
            RemoteKey::Mute => 164,
            RemoteKey::PlayPause => 85,
            RemoteKey::Next => 87,
            RemoteKey::Prev => 88,
            RemoteKey::Rewind => 89,
            RemoteKey::FastForward => 90,
            RemoteKey::Stop => 86,
            RemoteKey::ChannelUp => 166,
            RemoteKey::ChannelDown => 167,
            RemoteKey::Settings => 176,    // KEYCODE_SETTINGS
            RemoteKey::Input => 178,       // KEYCODE_TV_INPUT
            RemoteKey::Assist => 219,      // KEYCODE_ASSIST (opens Google Assistant)
        }
    }
}

#[derive(Debug)]
pub enum TvCmd {
    Key(RemoteKey),
    RawKey(i32), // arbitrary Android keycode (from the debug probe)
    LaunchApp(String),
    SubmitPin(String),
    SetImeText(String), // live text-entry: set the focused field's contents (IME)
    SubmitText,         // commit the typed query (KEYCODE_ENTER)
}

#[derive(Debug)]
pub enum TvEvent {
    Connected { name: String },
    Disconnected,
    VolumeChanged { level: u8, max: u8, muted: bool },
    PairingRequired,
    PairingOk,
    PairingFailed(String),
    /// The TV reports a text field is focused (IME) and ready for input.
    TextFieldActive(bool),
    Error(String),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkState {
    Down,
    Pairing,
    Connecting,
    Connected,
}

#[derive(Debug)]
pub enum InputMode {
    Normal,
    HostEntry { entered: String },
    PinEntry { entered: String, error: Option<String> },
    KeyProbe { entered: String, last: Option<String> },
    /// Live text entry: `buffer` mirrors to the TV's focused field via IME;
    /// `field_active` tracks whether the TV currently has a field focused.
    TextInput { buffer: String, field_active: bool },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keycodes_match_android() {
        assert_eq!(RemoteKey::Home.keycode(), 3);
        assert_eq!(RemoteKey::Back.keycode(), 4);
        assert_eq!(RemoteKey::Up.keycode(), 19);
        assert_eq!(RemoteKey::Mute.keycode(), 164);
    }
}
