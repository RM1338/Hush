#!/bin/sh
# Download GRID (CC BY 4.0) video + word alignments into data/grid, unzip, delete zips (D-42).
# Resumable: finished parts are skipped, partial zips continue.
# Zenodo gives ~350 KB/s per connection, so 4 downloads run at once (~3 h for all 13.6 GB).
#   training/fetch_grid.sh        all parts
#   training/fetch_grid.sh s1     one part
set -e
cd "$(dirname "$0")/.." && mkdir -p data/grid && cd data/grid
if [ -n "$1" ]; then
  [ -f "$1.done" ] && exit 0
  curl -sSfL -C - -o "$1.zip" "https://zenodo.org/records/3625687/files/$1.zip?download=1"
  unzip -qo "$1.zip" && rm "$1.zip" && touch "$1.done" && echo "done $1"
  exit 0
fi
printf '%s\n' alignments s1 s2 s3 s4 s5 s6 s7 s8 s9 s10 s11 s12 s13 s14 s15 s16 s17 s18 s19 s20 \
  s22 s23 s24 s25 s26 s27 s28 s29 s30 s31 s32 s33 s34 \
  | xargs -P 4 -n 1 "$(pwd)/../../training/fetch_grid.sh"
