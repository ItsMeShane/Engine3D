package editor.customComponents;

import editor.customUI.JTabbedPaneUI;

import javax.swing.*;
import java.awt.*;

public class JTabbedPaneCustom extends JTabbedPane {

    public JTabbedPaneCustom() {
        setBackground(new Color(250, 250, 250));
        setTabLayoutPolicy(javax.swing.JTabbedPane.SCROLL_TAB_LAYOUT);
        setUI(new JTabbedPaneUI());
    }
}