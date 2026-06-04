// /home/jane/projects/clicker/src/proto.rs
// Generated protobuf types from proto/*.proto, compiled by build.rs (prost).
// One include! per proto package; the file name prost emits is "<package>.rs".
// Adjust the string literals below if Step 1's grep showed a different package.

pub mod polo {
    // polo.proto declares `package polo.wire.protobuf;`, so prost emits
    // "polo.wire.protobuf.rs".
    include!(concat!(env!("OUT_DIR"), "/polo.wire.protobuf.rs"));
}

pub mod remotemessage {
    // Protocol tasks reference these types as `crate::proto::remotemessage::*`.
    // prost emits "<package>.rs"; adjust this filename to match Step 1's grep output.
    include!(concat!(env!("OUT_DIR"), "/remote.rs"));
}
