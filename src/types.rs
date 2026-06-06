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
    LaunchApp(String),
    SubmitPin(String),
}

#[derive(Debug)]
pub enum TvEvent {
    Connected { name: String },
    Disconnected,
    VolumeChanged { level: u8, max: u8, muted: bool },
    PairingRequired,
    PairingOk,
    PairingFailed(String),
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
