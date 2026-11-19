# ICC profile of the print file's output intent

`PrintReadyStamp` embeds this profile as the `GTS_PDFX` output intent of every approved print file
(`stockflow.design.print.output-intent`, kltn-docs 12 §4.5, open question B35).

| File | Output intent | Source | Licence |
| --- | --- | --- | --- |
| `ISOcoated_v2_eci.icc` | `FOGRA39`, "ISO Coated v2 (ECI)", registry `http://www.color.org` | European Color Initiative, package `eci_offset_2009.zip` from https://www.eci.org/doku.php?id=en:downloads (file dated 2007-02-28, 1 829 077 bytes, SHA-256 `128dc02f7246cc3807af0323695379f64151a8f27a587736acc59f8b6ce894b8`) | ECI offset profiles are free to download, use and pass on |

It is byte for byte the file the storefront embeds when it exports `PRINT_READY`
(`fe-storefront-stockflow-commerce`, `public/print/icc/ISOcoated_v2_eci.icc`, whose README records the same source and
terms), so the intent the server stamps is the one the studio drew for.

The storefront's choice of 2026-11-18, which the server follows: paper cups and bags print to the generic FOGRA39 condition until a
printer names its own profile; the printer's RIP converts from it. Plastic cups (dry offset, white underlay) need their
own printing condition and are not covered here.

Do not edit the file. To use another printing condition, point `DESIGN_PRINT_ICC_PROFILE` at its profile
(`classpath:`, `file:` or a plain path) and set `DESIGN_PRINT_OUTPUT_CONDITION` (required with any profile but this one)
and `DESIGN_PRINT_OUTPUT_INFO`.
