package editor;

import editor.customComponents.JSliderCustom;
import editor.customComponents.JTabbedPaneCustom;
import entities.Camera;
import entities.Entity;
import entities.Light;
import renderEngine.GraphicsSettings;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Editor extends JFrame {

    private final List<Entity> entities;
    private final List<Light> lights;
    private final Camera camera;


    public static Map<Editable, ObjectSettings> settingsMap = new HashMap<>();

//    public static List<RawModel> rawModelBank = new ArrayList<>();
//    public static List<ModelTexture> textureBank = new ArrayList<>();


    public Editor(List<Entity> entities, List<Light> lights, Camera camera) {
        this.entities = entities;
        this.lights = lights;
        this.camera = camera;

        setTitle("Scene Editor");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setPreferredSize(new Dimension(425, 600));
        setMinimumSize(getPreferredSize());
        setAlwaysOnTop(true);

        initComponents();
        setVisible(true);
    }



    private void initComponents() {
        // tabbed panel
        JTabbedPaneCustom tabbedPaneCustom = new JTabbedPaneCustom();

        // settings tab
        JPanel entitiesTab = createEntitiesTab();
        tabbedPaneCustom.addTab("Entities", entitiesTab);

        // lights tab
        JPanel lightsTab = createLightsTab();
        tabbedPaneCustom.addTab("Lights", lightsTab);

        // camera tab
        JPanel cameraTab = createCameraTab();
        tabbedPaneCustom.addTab("Camera", cameraTab);

        // "add" tab
        JPanel addEntitiesTab = createAddEntitiesTab();
        tabbedPaneCustom.addTab("Add", addEntitiesTab);

        // graphics settings tab
        tabbedPaneCustom.addTab("Graphics", createGraphicsTab());

        getContentPane().add(tabbedPaneCustom, BorderLayout.CENTER);
    }



    /** Copies each setting's current value into its control on the Graphics tab. */
    private final List<Runnable> graphicsRefreshers = new ArrayList<>();
    private boolean refreshingGraphics; // the controls are being set from the values: don't write them back

    /**
     * Every entry of GraphicsSettings, changed live: a check box for each switch, a list for each level (greyed out
     * while the switch it belongs to is off). A timer keeps the controls in step with changes made elsewhere,
     * such as the keys in the game window.
     */
    private JPanel createGraphicsTab() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        for (GraphicsSettings.Setting setting : GraphicsSettings.ALL) {
            c.gridy = row++;
            if (setting.isSwitch()) {
                JCheckBox box = new JCheckBox(setting.label() + "   [" + setting.hotkey() + "]");
                box.addActionListener(e -> {
                    if (!refreshingGraphics) setting.set(box.isSelected() ? 1 : 0);
                });
                graphicsRefreshers.add(() -> box.setSelected(setting.get() != 0));
                c.gridx = 0;
                c.gridwidth = 2;
                c.insets = new Insets(4, 6, 0, 6);
                form.add(box, c);
            } else {
                JLabel label = new JLabel(setting.label());
                JComboBox<String> list = new JComboBox<>();
                for (int choice : setting.choices()) list.addItem(setting.text(choice));
                list.addActionListener(e -> {
                    if (!refreshingGraphics && list.getSelectedIndex() >= 0) setting.set(setting.choices()[list.getSelectedIndex()]);
                });
                GraphicsSettings.Setting required = setting.requires() == null ? null : GraphicsSettings.find(setting.requires());
                graphicsRefreshers.add(() -> {
                    int index = -1;
                    for (int i = 0; i < setting.choices().length; i++) if (setting.choices()[i] == setting.get()) index = i;
                    if (list.getSelectedIndex() != index) list.setSelectedIndex(index);
                    boolean enabled = required == null || required.get() != 0;
                    list.setEnabled(enabled);
                    label.setEnabled(enabled);
                });
                c.gridx = 0;
                c.gridwidth = 1;
                c.weightx = 0;
                c.insets = new Insets(1, 32, 1, 6);
                form.add(label, c);
                c.gridx = 1;
                c.weightx = 1;
                c.insets = new Insets(1, 6, 1, 6);
                form.add(list, c);
            }
        }
        c.gridy = row;
        c.weighty = 1;
        form.add(Box.createGlue(), c); // keeps the rows at the top

        JLabel status = new JLabel(" ");
        JButton save = new JButton("Save");
        save.setToolTipText("Write the settings to " + GraphicsSettings.FILE.toAbsolutePath() + ": they are loaded at every start, also for recordings and benchmarks");
        save.addActionListener(e -> {
            try {
                GraphicsSettings.save();
                status.setText("Saved to " + GraphicsSettings.FILE);
            } catch (IOException ex) {
                status.setText("Could not save: " + ex.getMessage());
            }
        });
        JButton defaults = new JButton("Built-in defaults");
        defaults.addActionListener(e -> {
            GraphicsSettings.resetToDefaults();
            refreshGraphics();
            status.setText("Built-in defaults (not saved)");
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(save);
        buttons.add(defaults);
        buttons.add(status);

        JLabel help = new JLabel("<html><body style='width: 270px'>Changes apply at once. Save keeps them for the next start, recordings and "
                + "benchmarks. Keys in [ ] switch them in the game window.</body></html>");
        help.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(help, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        tab.add(scroll, BorderLayout.CENTER);
        tab.add(buttons, BorderLayout.SOUTH);

        refreshGraphics();
        new Timer(250, e -> refreshGraphics()).start();
        return tab;
    }

    private void refreshGraphics() {
        refreshingGraphics = true;
        try {
            for (Runnable refresher : graphicsRefreshers) refresher.run();
        } finally {
            refreshingGraphics = false;
        }
    }



    private JPanel createCameraTab() {
        DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<>();

        comboBoxModel.addElement("Camera");
        CardLayout cardLayout = new CardLayout();
        JPanel componentSettings = new JPanel();
        componentSettings.setLayout(cardLayout);

        JComboBox<String> componentComboBox = new JComboBox<>(comboBoxModel);
        componentComboBox.addActionListener(e -> cardLayout.show(componentSettings, (String) componentComboBox.getSelectedItem()));

        JPanel cameraPanel = createSettingsPanel(camera);
        componentSettings.add(cameraPanel, "Camera");

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(componentComboBox, BorderLayout.NORTH);
        tab.add(componentSettings, BorderLayout.CENTER);
        return tab;
    }



    private JPanel createLightsTab() {
        DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<>();

        for (Light light : lights) {
            comboBoxModel.addElement(String.valueOf(light.getName()));
        }

        CardLayout cardLayout = new CardLayout();
        JPanel componentSettings = new JPanel();
        componentSettings.setLayout(cardLayout);

        JComboBox<String> componentComboBox = new JComboBox<>(comboBoxModel);
        componentComboBox.addActionListener(e -> cardLayout.show(componentSettings, (String) componentComboBox.getSelectedItem()));

        for (Light light : lights) {
            JPanel lightPanel = createSettingsPanel(light);
            componentSettings.add(lightPanel, light.getName());
        }

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(componentComboBox, BorderLayout.NORTH);
        tab.add(componentSettings, BorderLayout.CENTER);
        return tab;
    }



    private JPanel createEntitiesTab() {
        DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<>();
        for (Entity entity : entities) {
            comboBoxModel.addElement(entity.getName());
        }

        CardLayout cardLayout = new CardLayout();
        JPanel componentSettings = new JPanel();
        componentSettings.setLayout(cardLayout);

        JComboBox<String> componentComboBox = new JComboBox<>(comboBoxModel);
        componentComboBox.addActionListener(e -> cardLayout.show(componentSettings, (String) componentComboBox.getSelectedItem()));

        for (Entity entity : entities) {
            JPanel entityPanel = createSettingsPanel(entity);
            componentSettings.add(entityPanel, entity.getName());
        }

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(componentComboBox, BorderLayout.NORTH);
        tab.add(componentSettings, BorderLayout.CENTER);
        return tab;
    }

    private JPanel createSettingsPanel(Object target) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        if (target instanceof Entity entity) {

            // rotation
            JSliderCustom xRotationSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 360, (int) entity.getRotX());
            JSliderCustom yRotationSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 360, (int) entity.getRotY());
            JSliderCustom zRotationSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 360, (int) entity.getRotZ());
            xRotationSlider.setDimensions(getPreferredSize().width-50, 35);
            yRotationSlider.setDimensions(getPreferredSize().width-50, 35);
            zRotationSlider.setDimensions(getPreferredSize().width-50, 35);
            xRotationSlider.addChangeListener(e -> entity.setRotX(xRotationSlider.getValue()));
            yRotationSlider.addChangeListener(e -> entity.setRotY(yRotationSlider.getValue()));
            zRotationSlider.addChangeListener(e -> entity.setRotZ(zRotationSlider.getValue()));

            panel.add(new JLabel("X Rotation:"));
            panel.add(xRotationSlider);
            panel.add(new JLabel("Y Rotation:"));
            panel.add(yRotationSlider);
            panel.add(new JLabel("Z Rotation:"));
            panel.add(zRotationSlider);

            // position
            JPanel spinnerHolder = new JPanel(new FlowLayout());
            spinnerHolder.setMaximumSize(new Dimension(getPreferredSize().width, 35));
            SpinnerNumberModel xModel = new SpinnerNumberModel(entity.getPosition().x, -100, 2400, 1);
            SpinnerNumberModel zModel = new SpinnerNumberModel(entity.getPosition().z, -100, 2400, 1);
            SpinnerNumberModel yModel = new SpinnerNumberModel(entity.getPosition().y, -100,  100, 1);
            JSpinner xSpinner = new JSpinner(xModel);
            JSpinner ySpinner = new JSpinner(yModel);
            JSpinner zSpinner = new JSpinner(zModel);
            xSpinner.addChangeListener(e -> entity.getPosition().x = Float.parseFloat(String.valueOf(xModel.getNumber())));
            ySpinner.addChangeListener(e -> entity.getPosition().y = Float.parseFloat(String.valueOf(yModel.getNumber())));
            zSpinner.addChangeListener(e -> entity.getPosition().z = Float.parseFloat(String.valueOf(zModel.getNumber())));

            spinnerHolder.add(new JLabel("X:"));
            spinnerHolder.add(xSpinner);
            spinnerHolder.add(new JLabel("Y:"));
            spinnerHolder.add(ySpinner);
            spinnerHolder.add(new JLabel("Z:"));
            spinnerHolder.add(zSpinner);
            panel.add(new JLabel("Position:"));
            panel.add(spinnerHolder);

            // scale
            JSliderCustom scaleSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 1, 100, (int) (entity.getScale() * 10));
            scaleSlider.addChangeListener(e -> entity.setScale(scaleSlider.getValue() / 10f));
            scaleSlider.setDimensions(getPreferredSize().width-50, 35);

            panel.add(new JLabel("Scale:"));
            panel.add(scaleSlider);

        } else if (target instanceof Light light) {

            // color
            panel.add(new JLabel("\nColor, RGB:\n"));
            JSliderCustom rSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 256, (int) (light.getColor().x * 256));
            JSliderCustom gSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 256, (int) (light.getColor().y * 256));
            JSliderCustom bSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 256, (int) (light.getColor().z * 256));
            rSlider.setDimensions(getPreferredSize().width-50, 35);
            gSlider.setDimensions(getPreferredSize().width-50, 35);
            bSlider.setDimensions(getPreferredSize().width-50, 35);
            rSlider.setForeground(Color.RED);
            gSlider.setForeground(Color.GREEN);
            bSlider.setForeground(Color.BLUE);
            rSlider.addChangeListener(e -> light.getColor().x = (float) rSlider.getValue() / 256);
            gSlider.addChangeListener(e -> light.getColor().y = (float) gSlider.getValue() / 256);
            bSlider.addChangeListener(e -> light.getColor().z = (float) bSlider.getValue() / 256);
            panel.add(rSlider);
            panel.add(gSlider);
            panel.add(bSlider);

            // attenuation
            panel.add(new JLabel("\nAttenuation, XYZ:\n"));
            JSliderCustom xSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 200, (int) (light.getAttenuation().x * 200));
            JSliderCustom ySlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 100, (int) (light.getAttenuation().y * 100));
            JSliderCustom zSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 100, (int) (light.getAttenuation().z * 100));
            xSlider.setDimensions(getPreferredSize().width-50, 35);
            ySlider.setDimensions(getPreferredSize().width-50, 35);
            zSlider.setDimensions(getPreferredSize().width-50, 35);
            xSlider.addChangeListener(e -> light.getAttenuation().x = (float) xSlider.getValue() / 1000f);
            ySlider.addChangeListener(e -> light.getAttenuation().y = (float) ySlider.getValue() / 10000f);
            zSlider.addChangeListener(e -> light.getAttenuation().z = (float) zSlider.getValue() / 10000000f);
            ySlider.addChangeListener(new ChangeListener() {
                @Override
                public void stateChanged(ChangeEvent e) {
                    System.out.println(light.getAttenuation());

                }
            });
            panel.add(xSlider);
            panel.add(ySlider);
            panel.add(zSlider);

            // position
            JPanel spinnerHolder = new JPanel(new FlowLayout());
            spinnerHolder.setMaximumSize(new Dimension(getPreferredSize().width, 35));
            // wide range so far-away lights (e.g. the sun) don't throw (minimum <= value <= maximum)
            SpinnerNumberModel xModel = new SpinnerNumberModel(light.getPosition().x, -10000, 10000, 1);
            SpinnerNumberModel zModel = new SpinnerNumberModel(light.getPosition().z, -10000, 10000, 1);
            SpinnerNumberModel yModel = new SpinnerNumberModel(light.getPosition().y, -10000, 10000, 1);
            JSpinner xSpinner = new JSpinner(xModel);
            JSpinner ySpinner = new JSpinner(yModel);
            JSpinner zSpinner = new JSpinner(zModel);
            xSpinner.addChangeListener(e -> light.getPosition().x = Float.parseFloat(String.valueOf(xModel.getNumber())));
            ySpinner.addChangeListener(e -> light.getPosition().y = Float.parseFloat(String.valueOf(yModel.getNumber())));
            zSpinner.addChangeListener(e -> light.getPosition().z = Float.parseFloat(String.valueOf(zModel.getNumber())));

            spinnerHolder.add(new JLabel("X:"));
            spinnerHolder.add(xSpinner);
            spinnerHolder.add(new JLabel("Y:"));
            spinnerHolder.add(ySpinner);
            spinnerHolder.add(new JLabel("Z:"));
            spinnerHolder.add(zSpinner);
            panel.add(new JLabel("Position:"));
            panel.add(spinnerHolder);

        } else if (target instanceof Camera) {

            // YAW
            JLabel lblYaw = new JLabel("Yaw (180):");
            JSliderCustom yawSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 360, 180);
            yawSlider.addChangeListener(e -> {
                camera.setAngleAroundFocusPoint(yawSlider.getValue());
                lblYaw.setText("Yaw ("+yawSlider.getValue()+"):");
            });
            panel.add(lblYaw);
            panel.add(yawSlider);

            // PITCH
            JLabel lblPitch = new JLabel("Pitch (180):");
            JSliderCustom pitchSlider = new JSliderCustom(JSliderCustom.HORIZONTAL, 0, 360, 180);
            pitchSlider.addChangeListener(e -> {
                camera.setAngleAboveFocusPoint(pitchSlider.getValue());
                lblPitch.setText("Pitch ("+pitchSlider.getValue()+"):");
            });

            panel.add(lblPitch);
            panel.add(pitchSlider);

        }

        ObjectSettings objectSettings = new ObjectSettings(panel);
        settingsMap.put((Editable) target, objectSettings);

        return panel;
    }






    private JPanel createAddEntitiesTab() {


        DefaultTableModel model = new DefaultTableModel(new Object[]{"Name", "objPath", "texturePath"}, 0);
        for (Entity entity : entities) {
            model.addRow(new Object[]{entity.getName(), entity.getObjPath(), entity.getTexturePath()});
        }



        JTable table = new JTable();
        table.setModel(model);



        table.getModel().addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.UPDATE) {
                int row = e.getFirstRow();
                int col = e.getColumn();

                if (col >= 0) {
                    Object value = model.getValueAt(row, col);
                    Entity entity = entities.get(row);

                    switch (col) {
                        case 0 -> entity.setName((String) value);
                        case 1 -> entity.setObjPath((String) value);
                        case 2 -> entity.setTexturePath((String) value, true);
                    }
                }
            }
        });

        JPanel tab = new JPanel(new BorderLayout());
        JScrollPane scrollPane = new JScrollPane(table);
        tab.add(scrollPane, BorderLayout.CENTER);
        return tab;
    }
    public static class ObjectSettings {

        private final List<JComponent> components;

        public ObjectSettings(JPanel panel) {
            components = new ArrayList<>();
            extractJComponents(panel);
//            System.out.println();
        }

        private void extractJComponents(Component component) {
            if (component instanceof JComponent) {
                components.add((JComponent) component);
            }

            if (component instanceof Container) {
                Component[] children = ((Container) component).getComponents();
                for (Component child : children) {
                    extractJComponents(child);
//                    System.out.println(child.getName());
                }
            }
        }

        public List<JComponent> getComponents() {
            return components;
        }

    }

}
