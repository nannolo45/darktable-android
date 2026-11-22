# darktable manual (development edition), masks: notes for P3d stage 2

Read by the supervising session on 22 Nov 2026 from docs.darktable.org/usermanual/development/en/darkroom/masking-and-blending/masks/
(pages: groups-and-operators, parametric, refinement-controls). Use these notes if your session cannot fetch the manual.
The pinned engine is a 5.7 nightly with flexi masks, which is what this edition describes.

## Groups and operators
- A mask is a tree of groups. Each group combines its members (elements or nested groups) with one operator.
  It starts from its bottom member and applies the operator to each member above it in turn. A nested group's
  result acts as one element for the group above. The whole mask is the top-level group; default operator union.
- Operators: maximum (union), minimum (intersection), difference (keeps the bottom member, subtracts every member
  above it: order matters), exclusion (areas covered by an odd number of members); soft variants: screen
  (a + b - ab), sum (capped at 100 %), product (ab).
- Inverting an element selects what it would leave out; inverting a group flips the combined result after the
  operator. A member's opacity scales it before combination; a group's opacity scales its result.
- Restructuring: wrap in group (member into a new group at its position); compose (member at the bottom of a new
  group with an empty group above it to draw in); simplify (removes unnamed groups that change nothing); group
  together (several members). Set a group's operator by tapping it; new elements go into the selected group;
  members can be dragged between or within groups.
- Drawn shapes, parametric elements and raster masks can sit in the same tree.

## Parametric elements
- Each element selects a range on one channel and gives each pixel an opacity 0..1. One element = one channel.
- Added from channel buttons: Lab: L, a, b, C, h. Display RGB: g, R, G, B, H, S, L. Scene RGB: g, R, G, B, Jz, Cz, hz.
  Changing the blend colour space removes parametric elements after a confirmation.
- Per element: opacity; input range slider (shown by default); output range slider (hidden by default, a toggle
  shows it; a set output range still applies when hidden; with both set, a pixel must satisfy both); boost factor
  in EV (scene-referred channels only, where it applies).
- Range slider with four markers: two filled inner markers bound full opacity, two open outer markers bound zero
  opacity, opacity interpolated between. Order fixed, may touch, never cross. Double tap resets (all at the
  extremes = whole range). A whole-range element shows a warning icon (it restricts nothing).
- Invert on the element row deselects the range instead (markers shown swapped).
- Each slider has a picker: drag a rectangle to set the range from that area's values.
- Elements in a group combine as any group does (union/intersection etc.); the same channel may appear twice.

## Refinement
- details threshold (0 = off; positive selects detailed areas, negative flat; from demosaic data; RAW only),
  feathering guide (output/input × before/after blur), feathering radius (0 = off), blurring radius (0 = off),
  mask brightness (named "mask opacity" in 5.6 and earlier), mask contrast.
- Feathering runs before blur. Refinements act on the selection: an element alone, a group after its operator, or
  the whole mask with nothing selected.
- Header: bypass (keeps settings), reset. Display mask: yellow overlay.
