---
name: Old Horror
colors:
  surface: '#131313'
  surface-dim: '#131313'
  surface-bright: '#3a3939'
  surface-container-lowest: '#0e0e0e'
  surface-container-low: '#1c1b1b'
  surface-container: '#201f1f'
  surface-container-high: '#2a2a2a'
  surface-container-highest: '#353534'
  on-surface: '#e5e2e1'
  on-surface-variant: '#e3beb8'
  inverse-surface: '#e5e2e1'
  inverse-on-surface: '#313030'
  outline: '#aa8984'
  outline-variant: '#5a403c'
  surface-tint: '#ffb4a8'
  primary: '#ffb4a8'
  on-primary: '#690000'
  primary-container: '#8b0000'
  on-primary-container: '#ff907f'
  inverse-primary: '#b52619'
  secondary: '#d7c3b0'
  on-secondary: '#3a2e21'
  secondary-container: '#544738'
  on-secondary-container: '#c8b5a3'
  tertiary: '#c8c6c6'
  on-tertiary: '#303030'
  tertiary-container: '#424242'
  on-tertiary-container: '#b0aeae'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffdad4'
  primary-fixed-dim: '#ffb4a8'
  on-primary-fixed: '#410000'
  on-primary-fixed-variant: '#920703'
  secondary-fixed: '#f4dfcb'
  secondary-fixed-dim: '#d7c3b0'
  on-secondary-fixed: '#241a0e'
  on-secondary-fixed-variant: '#524436'
  tertiary-fixed: '#e4e2e2'
  tertiary-fixed-dim: '#c8c6c6'
  on-tertiary-fixed: '#1b1c1c'
  on-tertiary-fixed-variant: '#474747'
  background: '#131313'
  on-background: '#e5e2e1'
  surface-variant: '#353534'
typography:
  headline-lg:
    fontFamily: Newsreader
    fontSize: 48px
    fontWeight: '700'
    lineHeight: '1.1'
    letterSpacing: -0.04em
  headline-md:
    fontFamily: Newsreader
    fontSize: 32px
    fontWeight: '600'
    lineHeight: '1.2'
  headline-sm:
    fontFamily: Newsreader
    fontSize: 24px
    fontWeight: '600'
    lineHeight: '1.2'
  body-lg:
    fontFamily: Newsreader
    fontSize: 18px
    fontWeight: '400'
    lineHeight: '1.6'
  body-md:
    fontFamily: Newsreader
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  label-lg:
    fontFamily: Space Grotesk
    fontSize: 14px
    fontWeight: '500'
    lineHeight: '1.2'
  label-sm:
    fontFamily: Space Grotesk
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.2'
    letterSpacing: 0.1em
spacing:
  margin: 2rem
  gutter: 1.5rem
  unit: 4px
  stack-sm: 8px
  stack-md: 16px
  stack-lg: 32px
---

## Brand & Style

This design system is built on a foundation of **Tactile Brutalism**. It seeks to evoke the dread of uncovering a forbidden relic—a cursed manuscript or a forgotten anatomical study. The personality is grim, scholarly, and unforgiving. It targets an audience that appreciates the macabre, the occult, and the aesthetic of vintage dark fantasy.

The visual style rejects modern cleanliness in favor of grit and physical presence. It utilizes weathered textures, ink bleeds, and "imperfect" layouts to simulate a physical artifact. Every interaction should feel like turning a heavy, dust-caked page or etching ink into bone.

## Colors

The palette is dominated by "The Void" (Deep Black) and "Cinders" (Charcoal Gray), providing a low-contrast, atmospheric base. "Dried Blood" (Primary Red) is used sparingly for critical warnings, active states, and visceral accents. "Aged Vellum" (Secondary Parchment) provides the necessary contrast for readability, acting as the primary surface for text and iconography.

To maintain the eerie atmosphere, gradients should never be smooth; instead, use dithering or grain textures to transition between shades.

## Typography

The typography leverages **Newsreader** to provide a literary, gothic feel that mimics the printed word of a 19th-century horror novel. It should be typeset with slightly tighter tracking for headlines to create a sense of density and claustrophobia.

**Space Grotesk** serves as the functional "typewriter" font for metadata, labels, and technical data. This juxtaposition between the romanticism of the serif and the mechanical coldness of the grotesque font reinforces the "occult research" narrative. All text should have a slight opacity reduction (85-90%) to simulate ink soaking into paper.

## Layout & Spacing

This design system employs a **Fixed Grid** model reminiscent of a book layout. Content is centered with wide, generous margins that create a sense of isolation. The spacing rhythm is based on a 4px unit, but alignment should feel intentionally "off" in places—avoiding perfect symmetry to enhance the hand-drawn feel.

The layout should prioritize verticality, mimicking a scroll or a ledger. Use heavy vertical borders to separate sections rather than clean horizontal divisions.

## Elevation & Depth

Standard shadows are forbidden. Depth is instead conveyed through **Tonal Layering** and **Material Texture**:
- **Base Layer:** A high-grain charcoal texture.
- **Mid Layer:** "Weathered Parchment" surfaces that appear to sit on top of the void.
- **Top Layer:** Blood-red highlights or high-contrast vellum for modals.

Instead of blurs, use "ink bleed" strokes (rough, irregular 1px outlines) to define edges. Modals should appear as "scraps of paper" pinned over the existing content, using high-grain film overlays to darken the background rather than a simple black opacity.

## Shapes

The shape language is strictly **Sharp (0px)**. Roundness is perceived as modern and friendly, which contradicts the design system's narrative. To simulate "rough edges," containers should use SVG masks that create subtle irregularities along the perimeter, making no two "boxes" look identical. 

Icons must be hand-drawn, featuring varied line weights and "broken" paths to resemble charcoal sketches or woodblock prints.

## Components

### Buttons
Buttons are styled as heavy ink-stamps. They feature solid "Aged Vellum" backgrounds with "The Void" text. On hover, the button should appear to "bleed," with the background color shifting to a dark crimson and the text becoming distressed.

### Cards
Cards are parchment scraps. They must have a subtle paper-grain texture and irregular, 1px "torn" borders. Content within cards should be dense, with little padding, to evoke a sense of crowded notes.

### Input Fields
Inputs are simple horizontal lines, resembling a ledger. When focused, the line should turn "Blood Red" and a subtle flickering "candlelight" glow (a very faint, animated amber shadow) can be applied.

### Icons & Occult Elements
Use symbols that resemble alchemical signs or skeletal anatomy. Every icon should look as if it were scratched into the surface with a nib pen.

### Progress Indicators
Avoid smooth loading bars. Use "Filling Vessels" where the progress is represented by a red liquid filling a container, or a series of tally marks being scratched into the UI.