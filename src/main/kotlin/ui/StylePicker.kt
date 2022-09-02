package ui

import ui.Utils.orElse
import utils.Constants
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*
import javax.swing.border.LineBorder
import javax.swing.plaf.basic.BasicBorders


/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JPanel.java to edit this template
 */

/**
 *
 * @author me
 */

data class HSV(
    val h: Double,
    val s: Double,
    val v: Double
) {
    /**
     * 0 = red
     * 1 = green
     * 2 = blue
     * 3 = red
     */
    fun toColor(): Color {
        fun ramp(a: Double, b: Double, c: Double, d: Double): Double {
            val h = this.h
            val res = when {
                a <= h && h <= b -> (h - a) / (b - a)
                b <= h && h <= c -> 1.0
                c <= h && h <= d -> 1.0 - (h - c) / (d - c)
                else -> 0.0
            }
            return res
        }

        val maxV = v
        val minV = v - v * s
        val rng = maxV - minV

        val pureValues = listOf(
            ramp(-1.0, -0.5, 0.5, 1.0) + ramp(2.0, 2.5, 3.5, 4.0),
            ramp(0.0, 0.5, 1.5, 2.0),
            ramp(1.0, 1.5, 2.5, 3.0),
        )
        val res = pureValues
            .map { minV + rng * it }
            .map { (it * 255).toInt().coerceIn(0, 255) }
            .let {
                Color(it[0], it[1], it[2])
            }

        return res
    }
}

fun main(args: Array<String>) {
    HSV(0.75, 0.1, 1.0).toColor()
}

class StylePicker(
    val parent: GraphView
) : JPanel() {
    var kl: KeyListener = object : KeyListener {
        override fun keyTyped(e: KeyEvent) {}

        override fun keyPressed(e: KeyEvent) {
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> parent.endStylePicker()
            }
        }

        override fun keyReleased(e: KeyEvent) {}
    }

    data class ColorStyle(
        val background: Color? = null,
        val foreground: Color? = null,
        val label: String = "😊",
    ) {

        companion object {
            fun fromHue(h: Double, s: Double = 0.2, v: Double = 1.0, label: String = "😊") = ColorStyle(
                background = HSV(h, s, v).toColor(),
                foreground = HSV(h, s * 2, ((1 - v) * 2).coerceIn(0.0, 0.5)).toColor(),
                label = label
            )

        }
    }

    val hues = listOf(
        0.0, //red
        40, //orange
        60, //yellow
        100, //green
        190, //blue
        230, //dark blue
        280, //purple
        320, //pink
    ).map { it.toDouble() / 360.0 * 3.0 }


    val valuesAndSaturations = listOf(
        (0.1 to 1.00) to (0.3 to 0.5),
        (0.2 to 1.00) to (0.2 to 0.5),
        (0.25 to 1.00) to (0.2 to 0.5),
        (0.3 to 1.00) to (0.3 to 0.5),
        (0.4 to 0.95) to (0.4 to 0.5),
        (0.45 to 0.90) to (0.45 to 0.5),
    )

    val saturationHue = listOf(
        0.20 to 0.0, //red
        0.35 to 40.0, //orange
        0.30 to 60.0, //yellow
        0.25 to 100.0, //green
        0.20 to 190.0, //blue
        0.15 to 230.0, //dark blue
        0.20 to 280.0, //purple
        0.20 to 320.0, //pink
    ).map { it.first to it.second/360.0*3.0}

    val saturationValueMultipliers = listOf(
        Triple(0.5, 1.0, 0.4),
        Triple(1.0, 1.0, 0.3),
        Triple(1.2, 0.9, 0.2)
    )

    val colorStyles = listOfNotNull(
        (0 .. (saturationHue.size - 1)).map {
            ColorStyle(
                background = null,
                foreground = null,
            )
        },
        saturationValueMultipliers.flatMap { (sm, bgv, fgv) ->
            saturationHue.map { (s, h) ->
                ColorStyle(
                    background = HSV(h, s*sm, bgv).toColor(),
                    foreground = HSV(h, s*sm, fgv).toColor(),
                )
            }
        },
        valuesAndSaturations.flatMap { (bg, fg) ->
            hues.map { h ->
                ColorStyle(
                    background = HSV(h, bg.first, bg.second).toColor(),
                    foreground = HSV(h, fg.first, fg.second).toColor(),
                )
            }
        }.takeIf { Constants.wantColorSampler },
    ).flatten()

    private fun initComponents() {
        var gridBagConstraints: GridBagConstraints
        ColorPickerPanel = JPanel()
        SizePickerPanel = JPanel()
        jLabel1 = JLabel()
        jSpinner1 = JSpinner()
        minusButton = JButton()
        jTextField1 = JTextField()
        plusButton = JButton()
        layout = GridBagLayout()
        ColorPickerPanel!!.layout = GridLayout(0, hues.size)


        colorStyles.forEach { style ->
            val b = JButton()
            b.background = style.background.orElse { Color.WHITE }
            b.foreground = style.foreground.orElse { Color.BLACK }
            b.text = style.label
            b.addActionListener {
                colorFieldClicked(style)
            }
            ColorPickerPanel!!.add(b)
        }

        gridBagConstraints = GridBagConstraints()
        gridBagConstraints.fill = GridBagConstraints.BOTH
        gridBagConstraints.weightx = 1.0
        gridBagConstraints.weighty = 1.0
        add(ColorPickerPanel, gridBagConstraints)
        SizePickerPanel!!.layout = GridLayout()
        jLabel1!!.text = "Size:"
        SizePickerPanel!!.add(jLabel1)
        SizePickerPanel!!.add(jSpinner1)
        minusButton!!.text = "-"
        //jPanel2!!.add(minusButton)
        jTextField1!!.text = "jTextField1"
        jTextField1!!.addActionListener { evt -> jTextField1ActionPerformed(evt) }
        //jPanel2!!.add(jTextField1)
        plusButton!!.text = "+"
        //jPanel2!!.add(plusButton)
        gridBagConstraints = GridBagConstraints()
        gridBagConstraints.gridx = 0
        gridBagConstraints.gridy = 1
        gridBagConstraints.weightx = 0.2
        gridBagConstraints.weighty = 0.2
        add(SizePickerPanel, gridBagConstraints)
        this.border = LineBorder(Color.BLACK)
    } // </editor-fold>

    private fun colorFieldClicked(style: StylePicker.ColorStyle) {
        parent.g.selectedNodes.forEach {
            it.setStyle(style.background, style.foreground)
        }
        parent.endStylePicker()
    }

    private fun jTextField1ActionPerformed(evt: ActionEvent) {
        // TODO add your handling code here:
    }

    // Variables declaration - do not modify
    private
    var minusButton: JButton? = null

    private
    var plusButton: JButton? = null

    private
    var jLabel1: JLabel? = null

    private
    var ColorPickerPanel: JPanel? = null

    private
    var SizePickerPanel: JPanel? = null

    private
    var jSpinner1: JSpinner? = null

    private
    var jTextField1: JTextField? =
        null // End of variables declaration

    /**
     * Creates new form gridTest
     */
    init {
        initComponents()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            /* Set the Nimbus look and feel */
            //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
            /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html
         */

            //</editor-fold>

            /* Create and display the form */

//            EventQueue.invokeLater {
//                val gt = gridTest()
//                gt.isVisible = true
//            }
        }
    }
}
