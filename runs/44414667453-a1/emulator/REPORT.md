# emulator-test run 44414667453

engine: engine-04477d32-3d802276c4ef

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:          966068 kB
MemAvailable:    4342604 kB
Linux localhost 5.15.119-android13-8-00034-gd34029c8258b-ab10871489 #1 SMP PREEMPT Wed Sep 27 18:42:24 UTC 2023 x86_64 Toybox
```

## emulator, threads=1 vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |

## emulator, threads=all vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |

## Linux 1 thread vs Linux all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0035-multiple-modules | ok | 1431 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0086-diffuse | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0096-lensfun | ok | 1369 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0165-demosaic-markesteijn-vng | ok | 1365 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0175-agx | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## timings (ms)
```
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	3521	0001-exposure	4	0	1433
0035-multiple-modules	1	0	27713	0035-multiple-modules	4	0	8990
0086-diffuse	1	0	14624	0086-diffuse	4	0	6783
0096-lensfun	1	0	10486	0096-lensfun	4	0	5151
0146-lens-metadata-xtransIV-modversion-7	1	0	18618	0146-lens-metadata-xtransIV-modversion-7	4	0	7428
0165-demosaic-markesteijn-vng	1	0	15803	0165-demosaic-markesteijn-vng	4	0	6859
0175-agx	1	0	5096	0175-agx	4	0	2568
--- linux
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	1763	0001-exposure	4	0	1175
0035-multiple-modules	1	0	27323	0035-multiple-modules	4	0	9843
0086-diffuse	1	0	8424	0086-diffuse	4	0	3745
0096-lensfun	1	0	9471	0096-lensfun	4	0	4224
0146-lens-metadata-xtransIV-modversion-7	1	0	16287	0146-lens-metadata-xtransIV-modversion-7	4	0	6723
0165-demosaic-markesteijn-vng	1	0	17193	0165-demosaic-markesteijn-vng	4	0	7297
0175-agx	1	0	4498	0175-agx	4	0	2170
```
