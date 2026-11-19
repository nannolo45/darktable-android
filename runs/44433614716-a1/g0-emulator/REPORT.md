# G0 emulator test, run 44433614716

test exit: 0, export comparison ok: 

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066148 kB
MemFree:          998436 kB
MemAvailable:    4417744 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid/files/engine
unpack: fresh, 7186 ms, 991 files, 63.459325 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (4978 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	7186
unpacked_files	991
unpacked_bytes	63459325
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	4978
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	686.619291
full_size	3908x2602
preview_first_ms	639.906484
preview_size	1024x681
export_ms	2027.125283
exposure_before	1.0
preview_after_change_ms	206.905155
region_first_ms	417.613377
region	1080x2400+1414+101
region_after_change_ms	397.706177
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	390.976432
history_modules	rawprepare.0=on temperature.0=on highlights.0=on demosaic.0=on colorin.0=on colorout.0=on gamma.0=on flip.0=on exposure.0=on
saved_xmp_guard_ok	true
raw_unchanged	true
```

## export vs darktable-cli (Linux)
```
