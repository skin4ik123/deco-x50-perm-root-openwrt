use std::{fs, path::PathBuf};
fn main() {
    let out = PathBuf::from(std::env::args().nth(1).expect("output directory"));
    fs::create_dir_all(&out).unwrap();
    for (name, body) in [
        ("identity", deco_core::scripts::IDENTITY.to_string()),
        ("monitor", deco_core::scripts::MONITOR.to_string()),
        ("processes", deco_core::scripts::PROCESSES.to_string()),
        ("clients", deco_core::scripts::CLIENTS.to_string()),
        ("ssh_health", deco_core::scripts::SSH_HEALTH.to_string()),
        ("files", deco_core::scripts::files("/tmp/fixture")),
        ("read_file", deco_core::scripts::read_file("/tmp/fixture/value.txt")),
        ("read_outside", deco_core::scripts::read_file("/tmp/fixture/outside/value.txt")),
        ("write_outside", deco_core::scripts::write_file("/tmp/fixture/outside/value.txt", "must not be written", "22222222222222222222222222222222")),
        ("write_link", deco_core::scripts::write_file("/tmp/fixture/link.txt", "must not be written", "33333333333333333333333333333333")),
        ("write_hardlink", deco_core::scripts::write_file("/tmp/fixture/hard.txt", "must not be written", "44444444444444444444444444444444")),
        (
            "write_file",
            deco_core::scripts::write_file(
                "/tmp/fixture/value.txt",
                "after: Русский текст\n",
                "11111111111111111111111111111111",
            ),
        ),
    ] {
        fs::write(out.join(format!("{name}.lua")), body).unwrap();
    }
}
