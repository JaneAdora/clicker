// /home/jane/projects/clicker/src/main.rs
mod proto;
mod types;
mod framing;
mod cert;
mod tls;
mod pairing;
mod config;
mod remote;
mod theme;
mod keymap;
mod ui;
mod app;

fn main() -> anyhow::Result<()> {
    // Stub entry point. Real lifecycle (panic hook, raw mode, alt screen,
    // tokio runtime block_on) is wired up in a later UI task.
    println!("clicker {}", env!("CARGO_PKG_VERSION"));
    Ok(())
}
