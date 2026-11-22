package editor.customUI;


import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;


public class JTabbedPaneUI extends BasicTabbedPaneUI {

    @Override
    protected void installDefaults() {
        super.installDefaults();
    }

    @Override
    protected Insets getTabInsets(int tabPlacement, int tabIndex) {
        return new Insets(5, 30, 5, 30);
    }


    @Override
    protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex, Rectangle iconRect, Rectangle textRect, boolean isSelected) {
    }


    @Override
    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int height = calculateTabAreaWidth(tabPlacement, runCount, maxTabHeight) - 1;
        g2.setColor(tabPane.getBackground());
        Area area = new Area(new RoundRectangle2D.Double(0, height, tabPane.getWidth(), tabPane.getHeight() - height, 15, 15));
        if (selectedIndex == 0) {
            area.add(new Area(new Rectangle2D.Double(0, height, 15, 15)));
        }
        g2.fill(area);
        g2.dispose();
    }

}