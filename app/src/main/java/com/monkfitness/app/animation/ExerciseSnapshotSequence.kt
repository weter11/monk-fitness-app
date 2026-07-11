package com.monkfitness.app.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Encapsulates a sequence of exercise snapshots, with support for producing
 * composite outputs like sprite sheets and contact sheets.
 */
class ExerciseSnapshotSequence(
    val snapshots: List<ExerciseSnapshot>
) {
    /**
     * Combines all snapshots into a single sprite sheet of specified column width.
     */
    fun toSpriteSheet(columns: Int = snapshots.size): Bitmap {
        if (snapshots.isEmpty()) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val frameWidth = snapshots[0].bitmap.width
        val frameHeight = snapshots[0].bitmap.height

        val numFrames = snapshots.size
        val cols = columns.coerceIn(1, numFrames)
        val rows = kotlin.math.ceil(numFrames.toFloat() / cols).toInt()

        val totalWidth = cols * frameWidth
        val totalHeight = rows * frameHeight

        val spriteSheet = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(spriteSheet)

        for (i in snapshots.indices) {
            val col = i % cols
            val row = i / cols
            val x = col * frameWidth
            val y = row * frameHeight
            canvas.drawBitmap(snapshots[i].bitmap, x.toFloat(), y.toFloat(), null)
        }

        return spriteSheet
    }

    /**
     * Combines all snapshots into a labeled contact sheet grid.
     */
    fun toContactSheet(
        columns: Int = 4,
        spacing: Int = 10,
        labelHeight: Int = 30,
        transparentBackground: Boolean = false,
        backgroundColor: Int = android.graphics.Color.WHITE
    ): Bitmap {
        if (snapshots.isEmpty()) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val frameWidth = snapshots[0].bitmap.width
        val frameHeight = snapshots[0].bitmap.height

        val numFrames = snapshots.size
        val cols = columns.coerceIn(1, numFrames)
        val rows = kotlin.math.ceil(numFrames.toFloat() / cols).toInt()

        val cellWidth = frameWidth
        val cellHeight = frameHeight + labelHeight

        val totalWidth = cols * cellWidth + (cols + 1) * spacing
        val totalHeight = rows * cellHeight + (rows + 1) * spacing

        val contactSheet = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(contactSheet)
        if (!transparentBackground) {
            canvas.drawColor(backgroundColor)
        }

        val textPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = (frameHeight * 0.05f).coerceIn(12f, 24f)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        for (i in snapshots.indices) {
            val col = i % cols
            val row = i / cols

            val x = spacing + col * (cellWidth + spacing)
            val y = spacing + row * (cellHeight + spacing)

            // Draw frame
            canvas.drawBitmap(snapshots[i].bitmap, x.toFloat(), y.toFloat(), null)

            // Draw label
            val labelX = x + cellWidth / 2f
            val labelY = y + frameHeight + labelHeight - (labelHeight * 0.3f)
            val label = String.format("F:%d (P:%.2f)", snapshots[i].frameIndex, snapshots[i].progress)
            canvas.drawText(label, labelX, labelY, textPaint)
        }

        return contactSheet
    }
}
