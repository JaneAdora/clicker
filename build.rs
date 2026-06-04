// /home/jane/projects/clicker/build.rs
fn main() {
    println!("cargo:rerun-if-changed=proto/polo.proto");
    println!("cargo:rerun-if-changed=proto/remotemessage.proto");
    let fds = protox::compile(["proto/polo.proto", "proto/remotemessage.proto"], ["proto/"])
        .expect("protox: failed to compile .proto");
    prost_build::Config::new()
        .compile_fds(fds)
        .expect("prost: failed to generate Rust from descriptors");
}
