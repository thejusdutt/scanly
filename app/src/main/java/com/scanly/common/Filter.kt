package com.scanly.common

/**
 * Page enhancement filters. GREYSCALE is explicitly first-class — its absence is a
 * documented gap in the leading FOSS scanner and a top user complaint.
 */
enum class Filter {
    COLOR,       // white-balanced, contrast-enhanced color
    GREYSCALE,   // true 8-bit grey (smaller files, legible text)
    BW,          // adaptive-threshold black & white
    MAGIC,       // auto contrast + shadow/illumination removal
    WHITEBOARD,  // glare/shadow flattened, background forced white, marker colors boosted
}
