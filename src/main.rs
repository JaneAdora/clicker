mod app;
mod cert;
mod config;
mod framing;
mod keymap;
mod pairing;
mod proto;
mod remote;
mod theme;
mod tls;
mod types;
mod ui;

use anyhow::Result;

const VERSION: &str = env!("CARGO_PKG_VERSION");

const HELP: &str = "\
clicker :: terminal Android TV remote

USAGE:
  clicker          Connect to the configured TV and open the remote.
  clicker --help   Print this message.
  clicker --version  Print version.

CONFIG:
  ~/.config/clicker/config.toml   TV host, name, pairing state, last volume.
  First run with no host prompts for the TV IP, then walks pairing (on-screen PIN).

KEYS: arrows = D-pad, Enter = select, +/- = volume, m = mute, ? = help, q = quit.
Press ? inside clicker for the full keymap.
";

fn main() -> Result<()> {
    // ---- arg parsing (roam shape: --help / --version, reject unknown --flags) ----
    let args: Vec<String> = std::env::args().skip(1).collect();
    for a in &args {
        match a.as_str() {
            "--help" | "-h" => {
                print!("{HELP}");
                return Ok(());
            }
            "--version" | "-V" => {
                println!("clicker {VERSION}");
                return Ok(());
            }
            other if other.starts_with("--") => {
                eprintln!("clicker: unknown flag: {other}\n\nTry: clicker --help");
                std::process::exit(2);
            }
            other => {
                eprintln!("clicker: unexpected argument: {other}\n\nTry: clicker --help");
                std::process::exit(2);
            }
        }
    }

    // ---- load persisted config + client identity (cert) before touching the screen ----
    let cfg = config::load();
    let identity = cert::load_or_generate(&config::dir())?;

    // ---- terminal lifecycle (roam ordering: panic hook FIRST, restore before unwrap) ----
    suite_term::panic::install_panic_hook();
    crossterm::terminal::enable_raw_mode()?;
    let mut stdout = std::io::stdout();
    crossterm::execute!(
        stdout,
        crossterm::terminal::EnterAlternateScreen,
        crossterm::terminal::SetTitle("clicker"),
    )?;
    let backend = ratatui::backend::CrosstermBackend::new(stdout);
    let mut terminal = ratatui::Terminal::new(backend)?;

    // ---- bridge to async: tokio confined to run() (NOT #[tokio::main]; spec §7.4) ----
    let rt = tokio::runtime::Runtime::new()?;
    let result = rt.block_on(app::run(&mut terminal, cfg, identity));

    // ---- restore terminal BEFORE propagating the loop's result ----
    crossterm::execute!(std::io::stdout(), crossterm::terminal::LeaveAlternateScreen)?;
    crossterm::terminal::disable_raw_mode()?;
    let _ = terminal.show_cursor();

    result
}
