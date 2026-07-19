package com.androidexpert35.audiophilemusicplayer.presentation.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.DurationMedium
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.EasingEmphasized
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.EasingEmphasizedAccelerate
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.EasingEmphasizedDecelerate
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.EasingStandard
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.PlayerSlideDamping
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens.PlayerSlideStiffness

/**
 * Shared motion tokens for expressive transitions across the app.
 *
 * Keeping timing centralised prevents each screen from drifting into a
 * different feel and helps the navigation shell and player animate cohesively.
 *
 * Easing curves follow the Material Design 3 motion specification:
 * - [EasingStandard]             — on-screen moves between two defined positions.
 * - [EasingEmphasizedDecelerate] — elements entering the screen (slide in from edge).
 * - [EasingEmphasizedAccelerate] — elements leaving the screen (slide out to edge).
 *
 * The player overlay uses a physics-based spring (see [PlayerSlideStiffness] and
 * [PlayerSlideDamping]) rather than a timed curve, which eliminates the slow-start
 * artefact of a symmetric ease and makes the sheet feel immediately responsive.
 */
object MotionTokens {
    /** Quick feedback for icon swaps and small state changes. */
    const val DurationShort = 180

    /** Standard transition for panel updates and content swaps. */
    const val DurationMedium = 300

    /** Emphasised transition for hero content and shell motion (used with tween). */
    const val DurationLong = 420

    /**
     * Symmetric Material 3 standard easing — best for elements already on-screen
     * moving to a new position (e.g. tab switches, card expansions).
     *
     * Corresponds to `CubicBezier(0.4, 0, 0.2, 1)` in the M3 spec.
     */
    val EasingStandard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /**
     * Fast-start decelerate easing for **entering** elements sliding onto the screen
     * (e.g. hero content fading/sliding in inside a surface already on-screen).
     *
     * Corresponds to `CubicBezier(0.05, 0.7, 0.1, 1)` in the M3 Emphasized spec.
     */
    val EasingEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /**
     * Fast-exit accelerate easing for **leaving** elements sliding off the screen.
     *
     * Corresponds to `CubicBezier(0.3, 0, 0.8, 0.15)` in the M3 Emphasized spec.
     */
    val EasingEmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /**
     * Legacy alias kept so existing call-sites using [EasingEmphasized] keep compiling.
     * Prefer [EasingEmphasizedDecelerate] for new code.
     */
    val EasingEmphasized: Easing = EasingEmphasizedDecelerate

    // ── Player overlay spring parameters ─────────────────────────────────────

    /**
     * Spring stiffness for the full-screen player overlay slide-in/out.
     *
     * [Spring.StiffnessMedium] (400 N/m) with critical damping settles in
     * approximately 300 ms — matching [DurationMedium] in feel but without
     * the slow-start artefact of a symmetric tween curve. The spring starts
     * at maximum velocity and decelerates naturally, which is what makes a
     * slide-in feel immediately responsive rather than "sticky".
     */
    const val PlayerSlideStiffness = Spring.StiffnessMedium

    /**
     * Damping ratio for the player overlay spring — critically damped so the
     * sheet settles smoothly without any overshoot or bounce.
     */
    const val PlayerSlideDamping = Spring.DampingRatioNoBouncy
}

