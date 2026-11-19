# G0 emulator test, run 44542491419

test exit: 0, export comparison ok: 

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066148 kB
MemFree:          989316 kB
MemAvailable:    4371128 kB
```

## start
```
engine dir: /data/user/0/app.dtandroid.g0/files/engine
unpack: fresh, 3946 ms, 991 files, 63.528245 MB
libraries loaded from app storage: 42 of 42
plugin files that dlopen() accepts: 152 of 152
dtb_init: ok (1623 ms)
engine: darktable 04477d3, 4 threads
darktable loaded: 97 of 97 processing modules, 8 of 8 formats, 6 of 6 storages
```

## results
```
unpack_fresh	true
unpack_ms	3946
unpacked_files	991
unpacked_bytes	63528245
libs_loaded	42/42
plugin_files_dlopen_ok	152/152
init_ms	1623
engine_version	04477d3
threads	4
darktable_plugins_loaded	111/111
iop_loaded	97/97
format_loaded	8/8
storage_loaded	6/6
open_ms	232.491275
full_size	3908x2602
preview_first_ms	272.599949
preview_size	1024x681
export_ms	988.649193
exposure_before	1.0
preview_after_change_ms	28.004993
region_first_ms	157.934782
region	1080x2400+1414+101
region_after_change_ms	206.874123
cancel_result	DTB_E_CANCELLED
cancel_return_ms_after_start	371.136683
history_modules	rawprepare.0=on temperature.0=on highlights.0=on demosaic.0=on colorin.0=on colorout.0=on gamma.0=on flip.0=on exposure.0=on
saved_xmp_guard_ok	true
raw_unchanged	true
```

## export vs darktable-cli (Linux)
```
