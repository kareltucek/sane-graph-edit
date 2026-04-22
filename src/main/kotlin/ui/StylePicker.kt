package ui

import graph_tools.NodeShape
import graph_tools.SetShapeCommand
import graph_tools.SetSizeCommand
import graph_tools.StyleNodesCommand
import graph_tools.StyleSnapshot
import ui.ColorStyle.PickerSet.colorStyles
import ui.ColorStyle.PickerSet.hues
import utils.Utils.orElse
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
        minusButton = JButton()
        plusButton = JButton()
        jLabel1!!.text = "Size:"
        minusButton!!.text = "-"
        plusButton!!.text = "+"
        plusButton!!.background = Constants.buttonGray
        minusButton!!.background = Constants.buttonGray

        jLabel2 = JLabel()
        ovalButton = JButton()
        rectButton = JButton()
        jLabel2!!.text = "Shape:"
        ovalButton!!.text = "⚪"
        rectButton!!.text = "⬜"
        ovalButton!!.background = Constants.buttonGray
        rectButton!!.background = Constants.buttonGray

        jSeparator = JSeparator()
        jSeparator!!.orientation = SwingConstants.VERTICAL

        jSpinner1 = JSpinner()
        jTextField1 = JTextField()
        layout = GridBagLayout()
        sizeField = JTextField()
        ColorPickerPanel!!.layout = GridLayout(0, hues.size)
        gridBagConstraints = GridBagConstraints()
        gridBagConstraints.fill = GridBagConstraints.BOTH
        gridBagConstraints.weightx = 1.0
        gridBagConstraints.weighty = 1.0
        add(ColorPickerPanel, gridBagConstraints)
        SizePickerPanel!!.layout = GridLayout()
        jTextField1!!.text = "jTextField1"
        jTextField1!!.addActionListener { evt -> jTextField1ActionPerformed(evt) }
        SizePickerPanel!!.add(jLabel1)
        SizePickerPanel!!.add(minusButton)
        SizePickerPanel!!.add(plusButton)
        SizePickerPanel!!.add(jSeparator)
        SizePickerPanel!!.add(jLabel2)
        SizePickerPanel!!.add(rectButton)
        SizePickerPanel!!.add(ovalButton)
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

        ovalButton!!.addActionListener {
            setShape(NodeShape.Oval)
        }
        rectButton!!.addActionListener {
            setShape(NodeShape.Rectangle)
        }

    } // </editor-fold>

    private fun setSize(r: Double) {
        val targets = parent.g.selectedNodes.toList()
        if (targets.isEmpty()) return
        val before = targets.associateWith { it.attributes.nodeScale }
        parent.g.commit(SetSizeCommand(parent.g, before, delta = r, absolute = null))
        parent.refreshStatus()
        parent.repaint()
    }

    private fun setShape(s: NodeShape) {
        val targets = parent.g.selectedNodes.toList()
        if (targets.isEmpty()) return
        val before = targets.associateWith { n ->
            // n.cache.shape is a NodeShapeImpl, but commands round-trip
            // through the NodeShape enum (which the setShape API accepts).
            // Recover the enum entry for each current shape.
            NodeShape.values().firstOrNull { it.impl === n.cache.shape }
        }
        parent.g.commit(SetShapeCommand(parent.g, before, s))
        parent.repaint()
    }

    private fun colorFieldClicked(style: ColorStyle) {
        val targets = parent.g.selectedNodes.toList()
        if (targets.isNotEmpty()) {
            val before = targets.associateWith { StyleSnapshot.of(it) }
            val after = targets.associateWith {
                StyleSnapshot(
                    bg = style.background,
                    fg = style.foreground,
                    // Colour picks don't touch node scale; preserve it.
                    nodeScale = it.attributes.nodeScale,
                )
            }
            parent.g.commit(StyleNodesCommand(parent.g, before, after))
        }
        parent.endStylePicker()
    }

    private fun jTextField1ActionPerformed(evt: ActionEvent) {
        // TODO add your handling code here:
    }

    private var jSeparator: JSeparator? = null
    private var ovalButton: JButton? = null
    private var rectButton: JButton? = null
    private var minusButton: JButton? = null
    private var plusButton: JButton? = null
    private var jLabel1: JLabel? = null
    private var jLabel2: JLabel? = null

    private var ColorPickerPanel: JPanel? = null
    private var SizePickerPanel: JPanel? = null

    private var jSpinner1: JSpinner? = null

    var sizeField: JTextField? = null

    private var jTextField1: JTextField? =
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
