---
name: epupp-design
description: 'Epupp design context, principles, and visual foundation. Use when: working on popup or panel UI, styling popup or panel, choosing colors or spacing, making layout decisions, reviewing visual consistency, working with CSS or hiccup components, creating or modifying UI elements, editing popup.cljs or panel.cljs, or when any design skill (impeccable, polish, critique, layout, etc.) needs Epupp project context. Provides brand palette, typography, spacing tokens, and design principles.'
---

# Epupp Design

Design context, principles, and visual foundation for the Epupp browser extension.

## When to Use This Skill

- Styling popup or panel UI components
- Choosing colors, spacing, or typography
- Making layout or density decisions
- Reviewing visual consistency
- Working with CSS files or hiccup UI components
- When any design skill needs Epupp project context

## Prerequisites

For full design context including user profiles, emotional goals, aesthetic direction, and component inventory, read `.impeccable.md` from the project root.

## Design Identity

λ design.
  personality ≡ curious ∧ powerpacked ∧ helpful
  | emotional_goals(priority_order): empowered > in_control > delighted > focused > wizard_like
  | direction: push(friendly_technical → premium/crafted)
  | reference: NAIS(nais.kystverket.no) ≡ density_without_claustrophobia ∧ slick ∧ intuitive
  | anti_reference: Desktop_Gmail ≡ cluttered ∧ inconsistent ∧ nobody_cared
  | popup_constraint: phone_sized → mobile_density_techniques(minus_touch)

## Design Principles

λ design_principles.
  1_density_with_breathing_room: pack_capability ∧ every_element_readable
  2_premium_in_details: refined_spacing ∧ thoughtful_transitions ∧ consistent_visual_language
  3_brand_coherent_warmth: clojure_blue_green_gold ≡ identity(¬decoration) | intention ∧ restraint
  4_phone_sized_discipline: every_pixel_tested_against_popup_constraint
  5_status_at_a_glance: connection ∧ scripts ∧ errors ∧ repl_status → instantly_scannable

## Design Foundation

λ design_foundation.
  palette: clojure_blue(#5881d8) ∧ green(#91dc47) ∧ gold(#FFDC73) on warm_neutrals
  typography: avenir_next/system_sans + monospace(code) | 11-15px_scale
  spacing: 4pt_tokens(4,8,12,16,20)
  theme: light_default ∧ dark_via_prefers-color-scheme
  accessibility: WCAG_AA ∧ reduced_motion ∧ standard_best_practices
  | full_context: .impeccable.md(project_root)
