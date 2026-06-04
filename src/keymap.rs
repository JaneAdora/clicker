// src/keymap.rs
use crate::types::{RemoteKey, TvCmd};
use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

/// What a keypress resolves to. Jane owns the final mapping (spec §5).
pub enum Action {
    Cmd(TvCmd),
    Quit,
    ShowHelp,
    CloseModal,
    EnterTextMode,
}

/// Map a Normal-mode key event to an Action (spec §5 draft — to be finalized by Jane).
pub fn map_normal(key: KeyEvent) -> Option<Action> {
    use KeyCode::*;
    let shift = key.modifiers.contains(KeyModifiers::SHIFT);

    let k = |rk: RemoteKey| Some(Action::Cmd(TvCmd::Key(rk)));

    match key.code {
        // D-pad
        Up => k(RemoteKey::Up),
        Down => k(RemoteKey::Down),
        Left => k(RemoteKey::Left),
        Right => k(RemoteKey::Right),
        Enter => k(RemoteKey::Select),

        // Navigation
        Backspace => k(RemoteKey::Back),
        Home => k(RemoteKey::Home),
        Char('g') => k(RemoteKey::Home),
        Char('o') => k(RemoteKey::Menu),

        // Volume
        Char('+') => k(RemoteKey::VolUp),
        Char('-') => k(RemoteKey::VolDown),
        Char('m') => k(RemoteKey::Mute),

        // Power — deliberate Shift+P only (avoid accidental power-off).
        // crossterm delivers Shift+p as Char('P'); match the capital BEFORE the
        // lowercase Char('p') => Prev arm below, or Power would be shadowed.
        Char('P') => k(RemoteKey::Power),
        Char('p') if shift => k(RemoteKey::Power),

        // Transport
        Char(' ') => k(RemoteKey::PlayPause),
        Char('n') => k(RemoteKey::Next),
        Char('p') => k(RemoteKey::Prev),
        Char(',') => k(RemoteKey::Rewind),
        Char('.') => k(RemoteKey::FastForward),
        Char('s') => k(RemoteKey::Stop),

        // Channel
        PageUp => k(RemoteKey::ChannelUp),
        PageDown => k(RemoteKey::ChannelDown),

        // App control
        Char('?') => Some(Action::ShowHelp),
        Char('i') => Some(Action::EnterTextMode),
        Char('q') => Some(Action::Quit),
        Esc => Some(Action::CloseModal),

        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::{KeyEvent, KeyModifiers};

    fn ev(code: KeyCode, mods: KeyModifiers) -> KeyEvent {
        KeyEvent::new(code, mods)
    }

    #[test]
    fn up_arrow_is_dpad_up() {
        let a = map_normal(ev(KeyCode::Up, KeyModifiers::NONE)).unwrap();
        assert!(matches!(a, Action::Cmd(TvCmd::Key(RemoteKey::Up))));
    }

    #[test]
    fn q_quits() {
        let a = map_normal(ev(KeyCode::Char('q'), KeyModifiers::NONE)).unwrap();
        assert!(matches!(a, Action::Quit));
    }

    #[test]
    fn question_mark_shows_help() {
        let a = map_normal(ev(KeyCode::Char('?'), KeyModifiers::NONE)).unwrap();
        assert!(matches!(a, Action::ShowHelp));
    }

    #[test]
    fn plus_is_volume_up() {
        let a = map_normal(ev(KeyCode::Char('+'), KeyModifiers::NONE)).unwrap();
        assert!(matches!(a, Action::Cmd(TvCmd::Key(RemoteKey::VolUp))));
    }

    #[test]
    fn shift_p_is_power() {
        // crossterm delivers Shift+p as Char('P'); accept both spellings.
        let a = map_normal(ev(KeyCode::Char('P'), KeyModifiers::SHIFT)).unwrap();
        assert!(matches!(a, Action::Cmd(TvCmd::Key(RemoteKey::Power))));
    }
}
