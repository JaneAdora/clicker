// /home/jane/projects/clicker/src/framing.rs
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Append `n` as a base-128 LEB128 varint (protobuf wire varint) to `buf`.
pub fn encode_varint(mut n: u64, buf: &mut Vec<u8>) {
    loop {
        let mut byte = (n & 0x7f) as u8;
        n >>= 7;
        if n != 0 {
            byte |= 0x80;
        }
        buf.push(byte);
        if n == 0 {
            break;
        }
    }
}

/// Read one base-128 varint from `r`.
pub async fn read_varint<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<u64> {
    let mut result: u64 = 0;
    let mut shift: u32 = 0;
    loop {
        let mut byte = [0u8; 1];
        r.read_exact(&mut byte).await?;
        let b = byte[0];
        result |= ((b & 0x7f) as u64) << shift;
        if b & 0x80 == 0 {
            return Ok(result);
        }
        shift += 7;
        if shift >= 64 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "varint too long",
            ));
        }
    }
}

/// Write a length-delimited message: varint(len) followed by `payload`.
pub async fn write_msg<W: AsyncWrite + Unpin>(w: &mut W, payload: &[u8]) -> std::io::Result<()> {
    let mut prefix = Vec::with_capacity(10);
    encode_varint(payload.len() as u64, &mut prefix);
    w.write_all(&prefix).await?;
    w.write_all(payload).await?;
    w.flush().await?;
    Ok(())
}

/// Read a length-delimited message: varint(len) then exactly that many bytes.
pub async fn read_msg<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Vec<u8>> {
    let len = read_varint(r).await? as usize;
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_roundtrip_across_boundaries() {
        for &n in &[0u64, 1, 127, 128, 300, 16384] {
            let mut buf = Vec::new();
            encode_varint(n, &mut buf);
            // decode synchronously off a slice via the async reader on a cursor
            let mut cursor = std::io::Cursor::new(buf.clone());
            let decoded = futures::executor::block_on(read_varint(&mut cursor)).unwrap();
            assert_eq!(decoded, n, "varint roundtrip failed for {n}");
        }
        // explicit byte-layout checks at the boundaries
        let mut b = Vec::new();
        encode_varint(0, &mut b);
        assert_eq!(b, vec![0x00]);
        b.clear();
        encode_varint(127, &mut b);
        assert_eq!(b, vec![0x7f]);
        b.clear();
        encode_varint(128, &mut b);
        assert_eq!(b, vec![0x80, 0x01]);
        b.clear();
        encode_varint(300, &mut b);
        assert_eq!(b, vec![0xac, 0x02]);
    }

    #[tokio::test]
    async fn write_then_read_msg_roundtrip() {
        let (mut a, mut b) = tokio::io::duplex(64);
        let payload = b"hello protocol v2".to_vec();
        let p2 = payload.clone();
        let writer = tokio::spawn(async move {
            write_msg(&mut a, &p2).await.unwrap();
        });
        let got = read_msg(&mut b).await.unwrap();
        writer.await.unwrap();
        assert_eq!(got, payload);
    }

    #[tokio::test]
    async fn read_msg_handles_large_len_over_one_varint_byte() {
        let (mut a, mut b) = tokio::io::duplex(1024);
        let payload = vec![0x5au8; 300]; // len 300 => 2-byte varint prefix
        let p2 = payload.clone();
        let writer = tokio::spawn(async move {
            write_msg(&mut a, &p2).await.unwrap();
        });
        let got = read_msg(&mut b).await.unwrap();
        writer.await.unwrap();
        assert_eq!(got.len(), 300);
        assert_eq!(got, payload);
    }
}
