package ui

import ui.ColorStyle.PickerSet.colorStyles
import ui.ColorStyle.PickerSet.hues
import ui.Utils.orElse
import utils.Constants
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*
import javax.swing.border.LineBorder


/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JPanel.java to edit this template
 */


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
        sizeField = JTextField()
        ColorPickerPanel!!.layout = GridLayout(0, hues.size)
        gridBagConstraints = GridBagConstraints()
        gridBagConstraints.fill = GridBagConstraints.BOTH
        gridBagConstraints.weightx = 1.0
        gridBagConstraints.weighty = 1.0
        add(ColorPickerPanel, gridBagConstraints)
        SizePickerPanel!!.layout = GridLayout()
        jLabel1!!.text = "Size:"
        SizePickerPanel!!.add(jLabel1)
        minusButton!!.text = "-"
        jTextField1!!.text = "jTextField1"
        jTextField1!!.addActionListener { evt -> jTextField1ActionPerformed(evt) }
        plusButton!!.text = "+"
        SizePickerPanel!!.add(minusButton)
        SizePickerPanel!!.add(plusButton)
        plusButton!!.background = Constants.buttonGray
        minusButton!!.background = Constants.buttonGray
        gridBagConstraints = GridBagConstraints()
        gridBagConstraints.gridx = 0
        gridBagConstraints.gridy = 1
        gridBagConstraints.weightx = 0.2
        gridBagConstraints.weighty = 0.2
        add(SizePickerPanel, gridBagConstraints)
        this.border = LineBorder(Color.BLACK)

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

        minusButton!!.addActionListener {
            setSize(-1.0)
        }
        plusButton!!.addActionListener {
            setSize(1.0)
        }
        sizeField!!.addActionListener {
            setSize(1.0)
        }

    } // </editor-fold>

    private fun setSize(r: Double) {
        parent.g.selectedNodes.forEach {
            it.setSize(relative = r)
        }
        parent.g.needsRecomputing(parent.g.selectedNodes)
        parent.repaint()
    }

    private fun colorFieldClicked(style: ColorStyle) {
        parent.g.selectedNodes.forEach {
            it.setStyle(style)
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

    var sizeField: JTextField? = null

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
