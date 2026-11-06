//! Strict TMP framing: one authenticated SSH loopback forward, no discovery or
//! unsupported opcode. Journaling/allowlists live in the provisioning engine.
use crate::Result;
use std::{
    io::{Read, Write},
    net::TcpStream,
    time::Duration,
};
const PLACEHOLDER: u32 = 0x5a6b7c8d;
fn u16be(b: &[u8], p: usize) -> u16 {
    u16::from_be_bytes(b[p..p + 2].try_into().unwrap())
}
fn u32be(b: &[u8], p: usize) -> u32 {
    u32::from_be_bytes(b[p..p + 4].try_into().unwrap())
}
fn packet(op: u16, number: u16, kind: u8, body: &[u8], offset: u32) -> Vec<u8> {
    let mut out = vec![1, 1, 5, 0];
    out.extend(((20 + body.len()) as u16).to_be_bytes());
    out.extend([0, 0, 0, 0, 0, 0]);
    out.extend(PLACEHOLDER.to_be_bytes());
    out.extend([1, 2]);
    out.extend(op.to_be_bytes());
    out.extend([kind, 0]);
    out.extend(number.to_be_bytes());
    out.extend(crc32fast::hash(body).to_be_bytes());
    out.extend((body.len() as u32).to_be_bytes());
    out.extend(offset.to_be_bytes());
    out.extend(body);
    let c = crc32fast::hash(&out);
    out[12..16].copy_from_slice(&c.to_be_bytes());
    out
}
struct Client {
    socket: TcpStream,
    number: u16,
}
impl Client {
    fn read(&mut self, n: usize) -> Result<Vec<u8>> {
        let mut b = vec![0; n];
        self.socket
            .read_exact(&mut b)
            .map_err(|_| "Connection to Deco closed. Check the operation result".to_string())?;
        Ok(b)
    }
    fn send(&mut self, b: &[u8]) -> Result<()> {
        self.socket
            .write_all(b)
            .map_err(|_| "Sending to Deco failed. Check the operation result".into())
    }
    fn exchange(&mut self, op: u16, body: &[u8]) -> Result<Vec<u8>> {
        if ![1, 0xf0, 0xc456].contains(&op) || body.len() > 8156 {
            return Err("Operation is outside the reviewed protocol".into());
        }
        self.number += 1;
        let num = self.number;
        self.send(&packet(op, num, 2, body, 0))?;
        let mut output = vec![];
        let mut expected = None;
        for _ in 0..128 {
            let prefix = self.read(4)?;
            if prefix == [1, 1, 4, 0] {
                continue;
            }
            if prefix != [1, 1, 5, 0] {
                return Err("Unsupported Deco protocol version".into());
            }
            let mut frame = prefix;
            frame.extend(self.read(12)?);
            let size = u16be(&frame, 4) as usize;
            if frame[6..8] != [0, 0] || !(20..=8176).contains(&size) {
                return Err("Invalid Deco response header".into());
            }
            frame.extend(self.read(size)?);
            let checksum = u32be(&frame, 12);
            frame[12..16].copy_from_slice(&PLACEHOLDER.to_be_bytes());
            if crc32fast::hash(&frame) != checksum
                || frame[16..18] != [1, 2]
                || frame[21] != 0
                || u16be(&frame, 18) != op
                || u16be(&frame, 22) != num
            {
                return Err("Deco response does not match its request".into());
            }
            let crc = u32be(&frame, 24);
            let total = u32be(&frame, 28) as usize;
            let offset = u32be(&frame, 32) as usize;
            match frame[20] {
                3 => std::thread::sleep(Duration::from_millis(50)),
                5 => {
                    if total > 131072
                        || offset != output.len()
                        || expected.is_some_and(|e| e != (total, crc))
                    {
                        return Err("Deco fragments are out of order".into());
                    }
                    expected = Some((total, crc));
                    output.extend(&frame[36..]);
                    if output.len() > total {
                        return Err("Invalid Deco response size".into());
                    }
                    if output.len() == total {
                        if crc32fast::hash(&output) != crc {
                            return Err("Deco response checksum did not match".into());
                        }
                        return Ok(output);
                    }
                }
                _ => return Err("Unknown Deco response type".into()),
            }
            self.send(&packet(op, num, 4, &[], output.len() as u32))?;
        }
        Err("Deco response exceeded the fragment limit".into())
    }
}
pub fn request(port: u16, op: u16, body: &[u8]) -> Result<Vec<u8>> {
    let socket = TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_secs(5))
        .map_err(|_| "Owner SSH channel is unavailable".to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_secs(15)))
        .map_err(|_| "Connection timed out".to_string())?;
    socket
        .set_write_timeout(Some(Duration::from_secs(15)))
        .map_err(|_| "Connection timed out".to_string())?;
    let mut c = Client { socket, number: 0 };
    c.send(&[1, 1, 1, 0])?;
    if c.read(4)? != [1, 1, 2, 0] {
        return Err("Unexpected Deco protocol greeting".into());
    }
    c.send(&[1, 1, 2, 0])?;
    if c.exchange(1, &[])?.len() != 32 {
        return Err("Deco protocol session could not be established".into());
    }
    c.exchange(op, body)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn wire() {
        let p = packet(0xf0, 7, 2, b"abc", 0);
        assert_eq!(p.len(), 39);
        assert_eq!(u16be(&p, 4), 23);
        assert_eq!(u16be(&p, 18), 0xf0);
        assert_eq!(u16be(&p, 22), 7);
        let mut q = p.clone();
        let c = u32be(&q, 12);
        q[12..16].copy_from_slice(&PLACEHOLDER.to_be_bytes());
        assert_eq!(c, crc32fast::hash(&q));
    }
    #[test]
    fn actual_fragment_reassembly_rejects_crc_and_offset_corruption() {
        use std::{net::TcpListener, thread};
        let listener = TcpListener::bind(("127.0.0.1", 9228)).expect("test port 9228 must be free");
        for case in 0..4 {
            let fake = listener.try_clone().unwrap();
            let server = thread::spawn(move || {
                let (mut stream, _) = fake.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                let mut header = [0; 36];
                stream.read_exact(&mut header).unwrap();
                let n = u16be(&header, 4) as usize - 20;
                let mut body = vec![0; n];
                stream.read_exact(&mut body).unwrap();
                let whole = b"fragmented-reply";
                let mut first = packet(0xf0, 1, 5, &whole[..5], 0);
                first[24..28].copy_from_slice(&crc32fast::hash(whole).to_be_bytes());
                first[28..32].copy_from_slice(&(whole.len() as u32).to_be_bytes());
                if case == 1 {
                    first[32..36].copy_from_slice(&2u32.to_be_bytes());
                }
                if case == 2 {
                    first[24..28].copy_from_slice(&1u32.to_be_bytes());
                }
                first[12..16].copy_from_slice(&PLACEHOLDER.to_be_bytes());
                let sum = crc32fast::hash(&first);
                first[12..16].copy_from_slice(&sum.to_be_bytes());
                if case == 3 {
                    first[12] ^= 1;
                }
                stream.write_all(&first).unwrap();
                if case == 0 || case == 2 {
                    let mut ack = [0; 36];
                    stream.read_exact(&mut ack).unwrap();
                    let mut last = packet(0xf0, 1, 5, &whole[5..], 5);
                    last[24..28].copy_from_slice(&crc32fast::hash(whole).to_be_bytes());
                    last[28..32].copy_from_slice(&(whole.len() as u32).to_be_bytes());
                    last[12..16].copy_from_slice(&PLACEHOLDER.to_be_bytes());
                    let sum = crc32fast::hash(&last);
                    last[12..16].copy_from_slice(&sum.to_be_bytes());
                    stream.write_all(&last).unwrap();
                }
            });
            let socket = TcpStream::connect(("127.0.0.1", 9228)).unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let result = Client { socket, number: 0 }.exchange(0xf0, b"abc");
            if case == 0 {
                assert_eq!(result.unwrap(), b"fragmented-reply");
            } else {
                assert!(result.is_err(), "corruption {case} accepted");
            }
            server.join().unwrap();
        }
    }
}
