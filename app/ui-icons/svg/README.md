# Icons

One SVG per icon, stroke style on a 24 x 24 grid, drawn in black (`#000000`): the app tints them with the
theme's colours. The file name is the icon's meaning (`power.svg` is `DtIcon.Power`, `chevronDown.svg` is
`DtIcon.ChevronDown`). At build time each file is converted to an Android vector drawable and the
registry `DtIcon` is generated from the file names (`app/ui-icons/build.gradle.kts`). Replacing an icon is
replacing one file; adding a file adds an icon.

Supported SVG: what Android's own converter (`Svg2Vector`) accepts; keep to `path`, `rect`, `circle`,
`line`, `polyline`, with `fill="none"` and a stroke.
