package editor.customComponents;

import editor.customUI.JSliderUI;

import javax.swing.*;
import java.awt.*;

public class JSliderCustom extends JSlider {

    public JSliderCustom(int orientation, int min, int max, int value) {
        if (value>max) {
            System.err.println("Slider Value Out of Range:\nmax = " + max + "\nvalue = " + value);
            value=max;
        }
        if (value<min) {
            System.err.println("Slider Value Out of Range:\nmin = " + min + "\nvalue = " + value);
            value=min;
        }

        this.orientation = orientation;
        setModel(new DefaultBoundedRangeModel(value, 0, min, max));
        updateUI();

        setOpaque(false);
        setBackground(new Color(180, 180, 180));
        setForeground(new Color(69, 124, 235));
        setUI(new JSliderUI(this));
    }

    public void setDimensions(int w, int h) {
        setMaximumSize(new Dimension(w, h));
    }

}