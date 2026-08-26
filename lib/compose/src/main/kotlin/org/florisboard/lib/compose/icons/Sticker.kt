/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.compose.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The sticker glyph: a rounded square with one corner peeled back.
 *
 * Drawn here because Material's icon set has no sticker — the nearest stand-ins are a stack of photos
 * or a sticky note, and both say "picture" rather than "sticker". This is the shape every messenger
 * uses for it, so it needs no explaining.
 *
 * Geometry, so a later change stays deliberate: the body is a 4..20 rounded square whose bottom-right
 * corner is cut away on the diagonal from (20, 13) to (13, 20); the peel is the triangle in that gap,
 * drawn as the folded-back underside. Two dots at (9.5, 10) and (14.5, 10) give it a face, which is
 * what distinguishes it from a bookmark at small sizes.
 */
@Suppress("UnusedReceiverParameter")
val Icons.Outlined.Sticker: ImageVector
    get() {
        if (_sticker != null) {
            return _sticker!!
        }
        _sticker = materialIcon(name = "Outlined.Sticker") {
            // Body with the corner cut off.
            materialPath {
                moveTo(19.0f, 3.0f)
                horizontalLineTo(5.0f)
                curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
                verticalLineTo(19.0f)
                curveTo(3.0f, 20.1f, 3.9f, 21.0f, 5.0f, 21.0f)
                horizontalLineTo(13.0f)
                lineTo(21.0f, 13.0f)
                verticalLineTo(5.0f)
                curveTo(21.0f, 3.9f, 20.1f, 3.0f, 19.0f, 3.0f)
                close()
                moveTo(5.0f, 5.0f)
                horizontalLineTo(19.0f)
                verticalLineTo(12.0f)
                horizontalLineTo(14.0f)
                curveTo(12.9f, 12.0f, 12.0f, 12.9f, 12.0f, 14.0f)
                verticalLineTo(19.0f)
                horizontalLineTo(5.0f)
                close()
            }
            // The peeled corner, folded back on itself.
            materialPath {
                moveTo(14.0f, 20.5f)
                verticalLineTo(14.0f)
                curveTo(14.0f, 13.45f, 14.45f, 13.0f, 15.0f, 13.0f)
                horizontalLineTo(20.5f)
                close()
            }
            // The face, which is what reads as "sticker" rather than "bookmark" at 24 dp.
            materialPath {
                moveTo(9.5f, 8.5f)
                curveTo(10.05f, 8.5f, 10.5f, 8.95f, 10.5f, 9.5f)
                curveTo(10.5f, 10.05f, 10.05f, 10.5f, 9.5f, 10.5f)
                curveTo(8.95f, 10.5f, 8.5f, 10.05f, 8.5f, 9.5f)
                curveTo(8.5f, 8.95f, 8.95f, 8.5f, 9.5f, 8.5f)
                close()
                moveTo(14.5f, 8.5f)
                curveTo(15.05f, 8.5f, 15.5f, 8.95f, 15.5f, 9.5f)
                curveTo(15.5f, 10.05f, 15.05f, 10.5f, 14.5f, 10.5f)
                curveTo(13.95f, 10.5f, 13.5f, 10.05f, 13.5f, 9.5f)
                curveTo(13.5f, 8.95f, 13.95f, 8.5f, 14.5f, 8.5f)
                close()
            }
        }
        return _sticker!!
    }

@Suppress("ObjectPropertyName")
private var _sticker: ImageVector? = null
