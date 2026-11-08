# train-fusion

An HMM that fuses Caltrain's two 511.org GTFS-RT feeds (trip updates and vehicle
positions) into a single estimate of where each train is. So far: the archiver that
collects the feeds.

## Setup

Nix flake + direnv: `direnv allow` gives you Python 3.12 with `gtfs-realtime-bindings`,
puts `bin/` on PATH, and loads `.env.local` (which must define `API_KEY_511_1` and
`API_KEY_511_2`).

```sh
python fetch.py            # archive the feeds into data/ (runs forever)
```

## Docs

- [docs/fetcher.md](docs/fetcher.md): the archiver, data layout, and what we learned about the 511 feeds
