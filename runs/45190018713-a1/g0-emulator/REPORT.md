# G0 emulator test, run 45190018713

test exit: 0, export comparison ok: 1

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:          923868 kB
MemAvailable:    4324468 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 4812 ms, 991 files, 63.685141 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (4149 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	4812
unpacked_files	991
unpacked_bytes	63685141
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	4149
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	258.705931
full_size	3908x2602
preview_first_ms	231.056454
preview_size	1024x681
export_ms	2268.876772
exposure_before	1.0
preview_after_change_ms	60.780362
region_first_ms	366.076027
region	1080x2400+1414+101
region_after_change_ms	203.916049
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	343.78516
history_modules	rawprepare.0=on temperature.0=on highlights.0=on demosaic.0=on colorin.0=on colorout.0=on gamma.0=on flip.0=on exposure.0=on
saved_xmp_guard_ok	true
raw_unchanged	true
```

## export vs darktable-cli (Linux)
```
size 3908x2602 max 26 mean 0.00092 psnr 75.73 differing_values_pct 0.0642
```

## benchmark
```
dt-android G0 benchmark (diagnostic test app, unofficial darktable port)
app g0-app-7243022377f0, engine darktable 04477d3
device: Google sdk_gphone64_x86_64 (AOSP ranchu)
android: 13 (API 33), ABI x86_64
cores: 4
memory: 6.2 GB total, 4.1 GB available
image: cr2 file, 3908x2602 after the edit (10.2 MP)
screen region: 1080x2400 at 100 %, +16 px margin
engine start: unpack 4812 ms, dtb_init 4149 ms, 111 of 111 plugins loaded

milliseconds
                                            1 thread   4 threads
open (import, XMP, history)                      138         465
preview 1024 px, first                           350         900
preview after exposure change                     90          56
screen region, first                             466         621
screen region after exposure change              284         263
full size render after exposure change          1695        1142
export JPEG full size                           2622        2662

Each thread setting starts from the RAW file (image closed, caches dropped).
```

## timings
```
install_s 4.9
instrumentation_s 32.3
```
