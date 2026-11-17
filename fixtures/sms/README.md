# Bank SMS samples

One folder per bank (`mellat`, `melli`, `saderat`, `tejarat`, `saman`, ...), one JSON file per case.
The format is in `contracts/device-api.md`, section 11.

`testbank/` holds **synthetic** samples for plumbing tests only. It is not a real bank.

Real samples come from the maintainers' own phones. For each real bank, collect at least:
deposit (card-to-card in), deposit (paya/satna if the bank sends them), withdrawal,
a dynamic-password (OTP) message, and a promotional message.
Anonymise names and digits with fake values of the same length; keep layout and line breaks exactly.
A bank template may be marked `verified` only when every one of its cases is `real-anonymised`.
