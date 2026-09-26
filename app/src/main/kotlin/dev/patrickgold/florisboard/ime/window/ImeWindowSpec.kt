/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.width
import kotlin.math.abs

/**
 * The window spec describes the computed window props and constraints which is used within the UI.
 */
sealed class ImeWindowSpec {
    /**
     * The computed window props, constrained to the root bounds.
     */
    abstract val props: ImeWindowProps

    /**
     * The constraints accompanying the root bounds and props.
     */
    abstract val constraints: ImeWindowConstraints

    abstract val userPreferredOptions: UserPreferredOptions

    // The margin is deflated off every side of a key cell, so at the top of the widened spacing range
    // (issue #365) it could eat the key itself: 300 % of the 5dp vertical base is 15dp per side, and a
    // keyboard the user has already shrunk to its minimum has rows barely twice that tall. Cap each
    // margin at a fraction of the cell it is taken out of, which always leaves the key the rest.
    val keyMarginH: Dp by lazy {
        // Minus the split gap: the cap is there to stop the margin from eating the key, so it has to be
        // taken against the cell the key really gets, which on a split keyboard excludes the gap (#362).
        val keyCellWidth = (props.keyboardWidth(constraints) - splitGap) / KEYS_PER_ROW
        (props.calcKeyMarginH(constraints) * userPreferredOptions.keySpacingFactorH)
            .coerceAtMost(keyCellWidth * MAX_KEY_MARGIN_FRACTION)
    }

    val keyMarginV: Dp by lazy {
        val keyCellHeight = calcRowHeight(props.keyboardHeight)
        (props.calcKeyMarginV(constraints) * userPreferredOptions.keySpacingFactorV)
            .coerceAtMost(keyCellHeight * MAX_KEY_MARGIN_FRACTION)
    }

    val fontScale: Float by lazy { props.calcFontScale(constraints) * userPreferredOptions.fontScale }

    /**
     * The width of the gap between the two halves of a split keyboard, or zero when this window is not
     * split (issue #362). Only the thumbs mode brings a gap along, so no mode check is needed here.
     */
    val splitGap: Dp by lazy { constraints.defSplitGap }

    /**
     * Calculate how a move gesture would change the computed props.
     *
     * @param offset The offset by which the gesture moved. Must be a real number pair.
     * @param rowCount The effective row count. Must be at least 4.
     * @param smartbarRowCount The effective Smartbar row count.
     *
     * @return A moved window spec, possibly unchanged.
     */
    abstract fun movedBy(
        offset: DpOffset,
        rowCount: Int,
        smartbarRowCount: Int,
    ): ImeWindowSpec

    /**
     * Calculate how a resize gesture would change the computed props.
     *
     * @param offset The offset by which the gesture resized. Must be a real number pair.
     * @param handle The resize handle. Determines from which angle the keyboard is resized.
     * @param rowCount The effective row count. Must be at least 4.
     * @param smartbarRowCount The effective Smartbar row count.
     *
     * @return A resized window spec, possibly unchanged.
     */
    abstract fun resizedBy(
        offset: DpOffset,
        handle: ImeWindowResizeHandle,
        rowCount: Int,
        smartbarRowCount: Int,
    ): ImeWindowSpec

    /**
     * Calculates the row height for given baseline [keyboardHeight].
     */
    fun calcRowHeight(keyboardHeight: Dp): Dp {
        return keyboardHeight / constraints.baselineRowCount
    }

    /**
     * Calculates the Smartbar row height for given baseline [keyboardHeight].
     */
    fun calcSmartbarRowHeight(keyboardHeight: Dp): Dp {
        val defRowHeight = calcRowHeight(constraints.defKeyboardHeight)
        val rowHeight = calcRowHeight(keyboardHeight)
        return defRowHeight * constraints.smartbarStaticScalingFactor +
            rowHeight * constraints.smartbarDynamicScalingFactor
    }

    // No upper bound on the rows (issue #418): it used to be 6, and the Hindi varnamala with a number row has
    // 7, so the first drag on a resize handle threw and took the keyboard down. Nothing here needs a ceiling.
    protected fun Dp.toEffective(rowCount: Int, smartbarRowCount: Int): Dp = let { keyboardHeight ->
        require(rowCount >= 4)
        require(smartbarRowCount in 0..2)
        val effKeyboardHeight = calcRowHeight(keyboardHeight) * rowCount
        val effSmartbarHeight = calcSmartbarRowHeight(keyboardHeight) * smartbarRowCount
        return effKeyboardHeight + effSmartbarHeight
    }

    protected fun Dp.toBaseline(rowCount: Int, smartbarRowCount: Int): Dp  = let { effKeyboardHeight ->
        require(rowCount >= 4)
        require(smartbarRowCount in 0..2)
        val staticSmartbarHeight = calcRowHeight(constraints.defKeyboardHeight * constraints.smartbarStaticScalingFactor * smartbarRowCount)
        val keyboardHeight = ((effKeyboardHeight - staticSmartbarHeight) * constraints.baselineRowCount) /
            (rowCount + constraints.smartbarDynamicScalingFactor * smartbarRowCount)
        return keyboardHeight
    }

    /**
     * Specialized spec for fixed mode.
     */
    data class Fixed(
        val fixedMode: ImeWindowMode.Fixed,
        override val props: ImeWindowProps.Fixed,
        override val constraints: ImeWindowConstraints.Fixed,
        override val userPreferredOptions: UserPreferredOptions,
    ) : ImeWindowSpec() {
        override fun movedBy(
            offset: DpOffset,
            rowCount: Int,
            smartbarRowCount: Int,
        ): ImeWindowSpec {
            var paddingLeft = props.paddingLeft
            var paddingRight = props.paddingRight
            var paddingBottom = props.paddingBottom

            if (offset.x < 0.dp) {
                // move to left
                val newPaddingLeft = (paddingLeft + offset.x)
                    .coerceAtLeast(0.dp)
                paddingRight -= (newPaddingLeft - paddingLeft)
                paddingLeft = newPaddingLeft
            } else {
                // move to right
                val newPaddingRight = (paddingRight - offset.x)
                    .coerceAtLeast(0.dp)
                paddingLeft -= (newPaddingRight - paddingRight)
                paddingRight = newPaddingRight
            }

            if (fixedMode == ImeWindowMode.Fixed.NORMAL && paddingLeft != 0.dp && paddingRight != 0.dp) {
                // maybe snap to center
                if (abs((paddingLeft - paddingRight).value).dp <= constraints.snapToCenterWidth) {
                    val avgPadding = (paddingLeft + paddingRight) / 2
                    paddingLeft = avgPadding
                    paddingRight = avgPadding
                }
            }

            paddingBottom = (paddingBottom - offset.y)
                .coerceAtMost(constraints.maxKeyboardHeight - props.keyboardHeight.toEffective(rowCount, smartbarRowCount))
                .coerceAtLeast(0.dp)

            val newProps = props.copy(
                paddingLeft = paddingLeft,
                paddingRight = paddingRight,
                paddingBottom = paddingBottom,
            ).constrained(constraints)
            val newSpec = copy(props = newProps)
            return newSpec
        }

        override fun resizedBy(
            offset: DpOffset,
            handle: ImeWindowResizeHandle,
            rowCount: Int,
            smartbarRowCount: Int,
        ): ImeWindowSpec {
            var keyboardHeight = props.keyboardHeight.toEffective(rowCount, smartbarRowCount)
            var paddingLeft = props.paddingLeft
            var paddingRight = props.paddingRight
            var paddingBottom = props.paddingBottom

            if (handle.top) {
                keyboardHeight = (keyboardHeight - offset.y)
                    .coerceAtLeast(constraints.minKeyboardHeight.toEffective(rowCount, smartbarRowCount))
                    .coerceAtMost(constraints.maxKeyboardHeight - paddingBottom)
            } else if (handle.bottom) {
                val newKeyboardHeight = (keyboardHeight + offset.y)
                    .coerceAtLeast(constraints.minKeyboardHeight.toEffective(rowCount, smartbarRowCount))
                    .coerceAtMost(keyboardHeight + paddingBottom)
                paddingBottom += (keyboardHeight - newKeyboardHeight)
                keyboardHeight = newKeyboardHeight
            }

            if (handle.left) {
                val newPaddingLeft = (paddingLeft + offset.x)
                    .coerceAtLeast(constraints.minPaddingHorizontal - paddingRight)
                    .coerceAtLeast(0.dp)
                    .coerceAtMost(max(constraints.rootBounds.width - paddingRight - constraints.minKeyboardWidth, 0.dp))
                paddingLeft = newPaddingLeft
            } else if (handle.right) {
                val newPaddingRight = (paddingRight - offset.x)
                    .coerceAtLeast(constraints.minPaddingHorizontal - paddingLeft)
                    .coerceAtLeast(0.dp)
                    .coerceAtMost(max(constraints.rootBounds.width - paddingLeft - constraints.minKeyboardWidth, 0.dp))
                paddingRight = newPaddingRight
            }

            val newProps = ImeWindowProps.Fixed(
                keyboardHeight = keyboardHeight.toBaseline(rowCount, smartbarRowCount),
                paddingLeft = paddingLeft,
                paddingRight = paddingRight,
                paddingBottom = paddingBottom,
            ).constrained(constraints)
            val newSpec = copy(props = newProps)
            return newSpec
        }
    }

    /**
     * Specialized spec for floating mode.
     */
    data class Floating(
        val floatingMode: ImeWindowMode.Floating,
        override val props: ImeWindowProps.Floating,
        override val constraints: ImeWindowConstraints.Floating,
        override val userPreferredOptions: UserPreferredOptions,
    ) : ImeWindowSpec() {
        override fun movedBy(
            offset: DpOffset,
            rowCount: Int,
            smartbarRowCount: Int,
        ): ImeWindowSpec {
            val newProps = props.copy(
                offsetLeft = props.offsetLeft + offset.x,
                offsetBottom = props.offsetBottom - offset.y,
            ).constrained(constraints)
            val newSpec = copy(props = newProps)
            return newSpec
        }

        override fun resizedBy(
            offset: DpOffset,
            handle: ImeWindowResizeHandle,
            rowCount: Int,
            smartbarRowCount: Int,
        ): ImeWindowSpec {
            var keyboardHeight = props.keyboardHeight.toEffective(rowCount, smartbarRowCount)
            var keyboardWidth = props.keyboardWidth
            var offsetLeft = props.offsetLeft
            var offsetBottom = props.offsetBottom

            if (handle.top) {
                val offsetTop = constraints.rootBounds.height - keyboardHeight - offsetBottom
                keyboardHeight = (keyboardHeight - offset.y.coerceAtLeast(-offsetTop))
                    .coerceAtLeast(constraints.minKeyboardHeight.toEffective(rowCount, smartbarRowCount))
                    .coerceAtMost(constraints.maxKeyboardHeight)
            } else if (handle.bottom) {
                val newKeyboardHeight = (keyboardHeight + offset.y.coerceAtLeast(-offsetBottom))
                    .coerceAtLeast(constraints.minKeyboardHeight.toEffective(rowCount, smartbarRowCount))
                    .coerceAtMost(constraints.maxKeyboardHeight)
                offsetBottom -= (newKeyboardHeight - keyboardHeight)
                keyboardHeight = newKeyboardHeight
            }

            if (handle.left) {
                val newKeyboardWidth = (keyboardWidth - offset.x.coerceAtLeast(-offsetLeft))
                    .coerceIn(constraints.minKeyboardWidth..constraints.maxKeyboardWidth)
                    .coerceAtMost(constraints.rootBounds.width - offsetLeft)
                offsetLeft -= (newKeyboardWidth - keyboardWidth)
                keyboardWidth = newKeyboardWidth
            } else if (handle.right) {
                keyboardWidth = (keyboardWidth + offset.x)
                    .coerceIn(constraints.minKeyboardWidth..constraints.maxKeyboardWidth)
                    .coerceAtMost(constraints.rootBounds.width - offsetLeft)
            }

            val newProps = ImeWindowProps.Floating(
                keyboardHeight = keyboardHeight.toBaseline(rowCount, smartbarRowCount),
                keyboardWidth = keyboardWidth,
                offsetLeft = offsetLeft,
                offsetBottom = offsetBottom,
            ).constrained(constraints)
            val newSpec = copy(props = newProps)
            return newSpec
        }
    }

    data class UserPreferredOptions(
        val keySpacingFactorH: Float,
        val keySpacingFactorV: Float,
        val fontScale: Float,
    )

    companion object {
        /**
         * The number of key cells a character row is divided into, mirroring the cell width
         * `TextKeyboardLayout` lays the keys out with. Only used as a reference for [keyMarginH].
         */
        private const val KEYS_PER_ROW = 10

        /**
         * The share of a key cell a single key margin may take up at most. Both sides are deflated, so
         * the key itself always keeps at least `1 - 2 *` this fraction of the cell.
         */
        private const val MAX_KEY_MARGIN_FRACTION = 0.3f

        /**
         * The fallback window spec. It does not guarantee the window fits into the root window, and assumes
         * the root window is zero-sized. In practice this is never used, but allows for always having rootInsets,
         * simplifying sme of the calculation logic.
         */
        val Fallback: ImeWindowSpec = Fixed(
            fixedMode = ImeWindowMode.Fixed.NORMAL,
            props = ImeWindowProps.Fixed(
                keyboardHeight = 250.dp,
                paddingLeft = 0.dp,
                paddingRight = 0.dp,
                paddingBottom = 0.dp,
            ),
            userPreferredOptions = UserPreferredOptions(
                keySpacingFactorH = 1f,
                keySpacingFactorV = 1f,
                fontScale = 1f,
            ),
            constraints = ImeWindowConstraints.Fixed.Normal(ImeInsets.Root.Zero),
        )
    }
}
