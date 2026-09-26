/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * The normal keyboard remembers its size per subtype (issue #418): a Hindi varnamala keyboard has six rows
 * where QWERTY has four, and shrinking it must not shrink English.
 */
class ImeWindowSubtypePropsTest : FunSpec({
    val english = 1_000L
    val hindi = 2_000L
    val phone = with(Density(2.625f, 1f)) { ImeInsets.Root.of(IntRect(0, 0, 1080, 2340)) }

    fun props(height: Dp) = ImeWindowProps.Fixed(
        keyboardHeight = height,
        paddingLeft = 0.dp,
        paddingRight = 0.dp,
        paddingBottom = 0.dp,
    )

    fun ImeWindowSpec.hasHeight(height: Dp): Boolean {
        return this is ImeWindowSpec.Fixed && abs(props.keyboardHeight.value - height.value) < 1e-3f
    }

    coroutineTestScope = true

    context("config") {
        test("the normal keyboard reads its subtype's size, and the shared one without") {
            val config = ImeWindowConfig(
                mode = ImeWindowMode.FIXED,
                fixedProps = mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp)),
                subtypeProps = mapOf(hindi to props(160.dp)),
            )
            config.fixedPropsFor(ImeWindowMode.Fixed.NORMAL, hindi) shouldBe props(160.dp)
            config.fixedPropsFor(ImeWindowMode.Fixed.NORMAL, english) shouldBe props(220.dp)
        }

        test("writing the normal keyboard touches that subtype and nothing else") {
            val config = ImeWindowConfig(
                mode = ImeWindowMode.FIXED,
                fixedProps = mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp)),
            ).withFixedProps(ImeWindowMode.Fixed.NORMAL, hindi, props(160.dp))
            config.fixedProps shouldBe mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp))
            config.subtypeProps shouldBe mapOf(hindi to props(160.dp))
        }

        test("one-handed and split keep one size, whatever the subtype") {
            val config = ImeWindowConfig(
                mode = ImeWindowMode.FIXED,
                fixedProps = mapOf(ImeWindowMode.Fixed.COMPACT to props(180.dp)),
                subtypeProps = mapOf(hindi to props(160.dp)),
            )
            config.fixedPropsFor(ImeWindowMode.Fixed.COMPACT, hindi) shouldBe props(180.dp)
            val written = config.withFixedProps(ImeWindowMode.Fixed.THUMBS, hindi, props(170.dp))
            written.fixedProps[ImeWindowMode.Fixed.THUMBS] shouldBe props(170.dp)
            written.subtypeProps shouldBe config.subtypeProps
        }
    }

    context("saved config") {
        test("a config without per-subtype sizes is written exactly as before") {
            val byType = mapOf(
                phone.formFactor.typeGuess to ImeWindowConfig(
                    mode = ImeWindowMode.FIXED,
                    fixedProps = mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp)),
                ),
            )
            val json = ImeWindowConfig.ByTypeSerializer.serialize(byType)
            json shouldNotContain "subtypeProps"
            ImeWindowConfig.ByTypeSerializer.deserialize(json) shouldBe byType
        }

        test("per-subtype sizes survive a round trip") {
            val byType = mapOf(
                phone.formFactor.typeGuess to ImeWindowConfig(
                    mode = ImeWindowMode.FIXED,
                    subtypeProps = mapOf(english to props(220.dp), hindi to props(160.dp)),
                ),
            )
            val json = ImeWindowConfig.ByTypeSerializer.serialize(byType)
            ImeWindowConfig.ByTypeSerializer.deserialize(json) shouldBe byType
        }
    }

    context("controller") {
        test("switching the subtype switches the size") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            prefs.keyboard.windowConfig.set(
                mapOf(
                    phone.formFactor.typeGuess to ImeWindowConfig(
                        mode = ImeWindowMode.FIXED,
                        fixedProps = mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp)),
                        subtypeProps = mapOf(hindi to props(160.dp)),
                    ),
                ),
            )
            prefs.localization.activeSubtypeId.set(english)
            val windowController = ImeWindowController(prefs, backgroundScope)
            windowController.updateRootInsets(phone)

            windowController.activeWindowSpec.first { it.hasHeight(220.dp) }
            prefs.localization.activeSubtypeId.set(hindi)
            windowController.activeWindowSpec.first { it.hasHeight(160.dp) }
            prefs.localization.activeSubtypeId.set(english)
            windowController.activeWindowSpec.first { it.hasHeight(220.dp) }
        }

        test("a resize is kept for the subtype it was made in") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            prefs.keyboard.windowConfig.set(
                mapOf(
                    phone.formFactor.typeGuess to ImeWindowConfig(
                        mode = ImeWindowMode.FIXED,
                        fixedProps = mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp)),
                    ),
                ),
            )
            prefs.localization.activeSubtypeId.set(hindi)
            val windowController = ImeWindowController(prefs, backgroundScope)
            windowController.updateRootInsets(phone)

            val specBefore = windowController.activeWindowSpec.first { it.hasHeight(220.dp) }
            windowController.editor.beginResizeGesture()
            val resized = specBefore
                .resizedBy(DpOffset(0.dp, 40.dp), ImeWindowResizeHandle.TOP, rowCount = 6, smartbarRowCount = 0)
                .shouldBeInstanceOf<ImeWindowSpec.Fixed>()
            windowController.editor.onSpecUpdated(resized)
            windowController.editor.endResizeGesture(resized)

            val config = windowController.activeWindowConfig.first { hindi in it.subtypeProps }
            config.subtypeProps shouldBe mapOf(hindi to resized.props)
            config.fixedProps[ImeWindowMode.Fixed.NORMAL] shouldBe props(220.dp)
            resized.props.keyboardHeight shouldNotBe 220.dp

            prefs.localization.activeSubtypeId.set(english)
            windowController.activeWindowSpec.first { it.hasHeight(220.dp) }
            prefs.localization.activeSubtypeId.set(hindi)
            windowController.activeWindowSpec.first { it.hasHeight(resized.props.keyboardHeight) }
        }

        test("a reset puts only the active subtype back to the default") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            prefs.keyboard.windowConfig.set(
                mapOf(
                    phone.formFactor.typeGuess to ImeWindowConfig(
                        mode = ImeWindowMode.FIXED,
                        fixedProps = mapOf(ImeWindowMode.Fixed.NORMAL to props(220.dp)),
                        subtypeProps = mapOf(english to props(250.dp), hindi to props(160.dp)),
                    ),
                ),
            )
            prefs.localization.activeSubtypeId.set(hindi)
            val windowController = ImeWindowController(prefs, backgroundScope)
            windowController.updateRootInsets(phone)
            windowController.activeWindowSpec.first { it.hasHeight(160.dp) }

            windowController.actions.resetFixedSize()

            val defaultProps = ImeWindowConstraints.of(phone, ImeWindowMode.Fixed.NORMAL).defaultProps
            val config = windowController.activeWindowConfig.first { it.subtypeProps[hindi] != props(160.dp) }
            config.subtypeProps[hindi] shouldBe defaultProps
            config.subtypeProps[english] shouldBe props(250.dp)
            config.fixedProps[ImeWindowMode.Fixed.NORMAL] shouldBe props(220.dp)
        }

        // The Hindi varnamala with a number row: 5 layout rows + number row + bottom row, the last layout row
        // merged into the shift row. The resize maths used to demand at most 6 and threw on the first drag.
        test("a keyboard with seven rows can be resized") {
            val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
            val windowController = ImeWindowController(prefs, backgroundScope)
            windowController.updateRootInsets(phone)
            val spec = windowController.activeWindowSpec.first { it !== ImeWindowSpec.Fallback }

            shouldNotThrowAny {
                spec.resizedBy(DpOffset(0.dp, -20.dp), ImeWindowResizeHandle.TOP, rowCount = 7, smartbarRowCount = 0)
                spec.movedBy(DpOffset(0.dp, -20.dp), rowCount = 7, smartbarRowCount = 0)
            }
        }
    }
})
