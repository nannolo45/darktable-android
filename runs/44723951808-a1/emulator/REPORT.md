# emulator-test run 44723951808

engine: engine-04477d32-6a4466ffd1f4

## device
```
sdk: 33
release: 13
abi: x86_64
page_size: 4096
cores: 4
MemTotal:        6066152 kB
MemFree:         1069460 kB
MemAvailable:    4451496 kB
Linux localhost 5.15.119-android13-8-00034-gd34029c8258b-ab10871489 #1 SMP PREEMPT Wed Sep 27 18:42:24 UTC 2023 x86_64 Toybox
```

## emulator, threads=1 vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0097-sigmoid | ok | 1363 x 2048 | 3.94e-02 | 4.95e-03 | 45.3 | 84.414 % |
| p1b-de-colorcal-off | ok | 1363 x 2048 | 7.68e-03 | 5.26e-04 | 64.5 | 0.067 % |
| p1b-de-sigmoid-contrast1 | ok | 1363 x 2048 | 4.02e-02 | 2.68e-03 | 50.4 | 19.972 % |
| p1b-de-sigmoid-contrast3 | ok | 1363 x 2048 | 1.01e-02 | 4.35e-04 | 66.2 | 0.019 % |
| p1b-de-sigmoid-off | ok | 1363 x 2048 | 7.35e-03 | 1.56e-07 | 109.0 | 0.000 % |
| p1b-de-sigmoid-rgbratio | ok | 1363 x 2048 | 6.72e-03 | 4.98e-04 | 65.3 | 0.001 % |
| p1b-de-sigmoid-skew05 | ok | 1363 x 2048 | 3.28e-02 | 2.51e-03 | 51.0 | 24.945 % |
| p1b-default-edit | ok | 1363 x 2048 | 7.41e-03 | 5.27e-04 | 64.6 | 0.024 % |

## emulator, threads=all vs Linux (all threads)
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0097-sigmoid | ok | 1363 x 2048 | 3.94e-02 | 4.95e-03 | 45.3 | 84.414 % |
| p1b-de-colorcal-off | ok | 1363 x 2048 | 7.68e-03 | 5.26e-04 | 64.5 | 0.067 % |
| p1b-de-sigmoid-contrast1 | ok | 1363 x 2048 | 4.02e-02 | 2.68e-03 | 50.4 | 19.972 % |
| p1b-de-sigmoid-contrast3 | ok | 1363 x 2048 | 1.01e-02 | 4.35e-04 | 66.2 | 0.019 % |
| p1b-de-sigmoid-off | ok | 1363 x 2048 | 7.35e-03 | 1.56e-07 | 109.0 | 0.000 % |
| p1b-de-sigmoid-rgbratio | ok | 1363 x 2048 | 6.72e-03 | 4.98e-04 | 65.3 | 0.001 % |
| p1b-de-sigmoid-skew05 | ok | 1363 x 2048 | 3.28e-02 | 2.51e-03 | 51.0 | 24.945 % |
| p1b-default-edit | ok | 1363 x 2048 | 7.41e-03 | 5.27e-04 | 64.6 | 0.024 % |

## Linux 1 thread vs Linux all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0097-sigmoid | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-colorcal-off | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-contrast1 | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-contrast3 | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-off | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-rgbratio | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-skew05 | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-default-edit | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## emulator 1 thread vs emulator all threads
| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |
|---|---|---|---|---|---|---|
| 0001-exposure | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| 0097-sigmoid | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-colorcal-off | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-contrast1 | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-contrast3 | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-off | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-rgbratio | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-de-sigmoid-skew05 | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |
| p1b-default-edit | ok | 1363 x 2048 | 0.00e+00 | 0.00e+00 | identical | 0.000 % |

## timings (ms)
```
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	3466	0001-exposure	4	0	1878
0097-sigmoid	1	0	16408	0097-sigmoid	4	0	6988
p1b-de-colorcal-off	1	0	3695	p1b-de-colorcal-off	4	0	1800
p1b-de-sigmoid-contrast1	1	0	5245	p1b-de-sigmoid-contrast1	4	0	2057
p1b-de-sigmoid-contrast3	1	0	4701	p1b-de-sigmoid-contrast3	4	0	2095
p1b-de-sigmoid-off	1	0	3136	p1b-de-sigmoid-off	4	0	1716
p1b-de-sigmoid-rgbratio	1	0	3397	p1b-de-sigmoid-rgbratio	4	0	1816
p1b-de-sigmoid-skew05	1	0	4200	p1b-de-sigmoid-skew05	4	0	2058
p1b-default-edit	1	0	4541	p1b-default-edit	4	0	2106
--- linux
test	threads	exit	ms	test	threads	exit	ms
0001-exposure	1	0	1509	0001-exposure	4	0	1035
0097-sigmoid	1	0	12221	0097-sigmoid	4	0	4731
p1b-de-colorcal-off	1	0	2002	p1b-de-colorcal-off	4	0	1247
p1b-de-sigmoid-contrast1	1	0	2607	p1b-de-sigmoid-contrast1	4	0	1458
p1b-de-sigmoid-contrast3	1	0	2634	p1b-de-sigmoid-contrast3	4	0	1450
p1b-de-sigmoid-off	1	0	2011	p1b-de-sigmoid-off	4	0	1179
p1b-de-sigmoid-rgbratio	1	0	2556	p1b-de-sigmoid-rgbratio	4	0	1332
p1b-de-sigmoid-skew05	1	0	2640	p1b-de-sigmoid-skew05	4	0	1478
p1b-default-edit	1	0	2698	p1b-default-edit	4	0	1475
```
