import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Portable, offline visual-asset viewer. Java 17; no external dependencies. */
public class BedrockItemViewer {
    static final Color BG = new Color(18,23,29), PANEL = new Color(27,34,41), TEXT = new Color(232,240,239),
        MUTED = new Color(167,185,188), GREEN = new Color(129,222,167);
    record Item(String id, String name, String category, String path, BufferedImage image) {}
    final List<Item> items = new ArrayList<>();
    final DefaultListModel<Item> model = new DefaultListModel<>();
    final JList<Item> list = new JList<>(model);
    final JTextField search = new JTextField(), itemId = new JTextField(), path = new JTextField();
    final JComboBox<String> category = new JComboBox<>(new String[]{"Alle","Blöcke","Kampf","Werkzeuge","Rüstung","Nahrung","Material","Ausrüstung","Kreaturen","Sonstiges","Import"});
    final JComboBox<String> mode = new JComboBox<>(new String[]{"mode.items","mode.all"});
    final JComboBox<L10n.Language> language = new JComboBox<>(L10n.Language.values());
    final JLabel brand = new JLabel("BEDROCK / MOD ITEM VIEWER  1.4"), title = new JLabel(), metadata = new JLabel(),
        status = new JLabel(" "), zoomLabel = new JLabel(), languageLabel = new JLabel(), hint = new JLabel();
    final JButton load = new JButton(), rotate = new JButton(), reset = new JButton(), copy = new JButton(), export = new JButton(), details = new JButton(), craft=new JButton();
    final JCheckBox grid = new JCheckBox(), bright = new JCheckBox();
    final JSlider zoom = new JSlider(1,24,16);
    final Preview preview = new Preview();
    final JPanel root = new JPanel(new BorderLayout(16,16)), filters = new JPanel(new BorderLayout(8,8));
    ModTextureImporter.Report report;
    final RecipeBook vanillaRecipes;
    Item selected;
    int rotation;
    boolean busy;
    String statusKey = "status.ready";
    Object[] statusArgs = {};

    static String t(String key, Object... args) { return L10n.t(key,args); }
    BedrockItemViewer() throws IOException {
        items.addAll(builtins());
        vanillaRecipes=RecipeBook.vanilla();
        root.setBorder(BorderFactory.createEmptyBorder(20,20,16,20));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JPanel header = new JPanel(new BorderLayout(10,0));
        brand.setFont(L10n.font(Font.BOLD,23)); brand.setForeground(GREEN); header.add(brand,BorderLayout.CENTER);
        JPanel languages = new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0)); languages.add(languageLabel); languages.add(language);
        language.setSelectedItem(L10n.language); header.add(languages,BorderLayout.EAST); top.add(header);
        JPanel importBar = new JPanel(new FlowLayout(FlowLayout.LEFT,8,10)); importBar.add(load); importBar.add(mode);
        details.setEnabled(false); importBar.add(details); top.add(importBar);
        hint.setForeground(MUTED); top.add(hint); root.add(top,BorderLayout.NORTH);
        load.addActionListener(e -> chooseImport()); details.addActionListener(e -> showDetails());
        language.addActionListener(e -> {
            boolean saved = L10n.saveLanguage((L10n.Language)language.getSelectedItem());
            applyLanguage();
            if (!saved) setStatus("language.unsaved");
        });
        mode.setRenderer(new KeyRenderer()); category.setRenderer(new CategoryRenderer());
        JPanel library = new JPanel(new BorderLayout(8,12));
        filters.add(search,BorderLayout.CENTER); filters.add(category,BorderLayout.SOUTH); library.add(filters,BorderLayout.NORTH);
        list.setCellRenderer(new ItemRenderer()); list.setFixedCellHeight(64); list.setFixedCellWidth(300); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setToolTipText(" ");
        library.add(new JScrollPane(list),BorderLayout.CENTER); library.setPreferredSize(new Dimension(320,500));
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && list.getSelectedValue()!=null) select(list.getSelectedValue()); });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        }); category.addActionListener(e -> filter());
        JPanel detail = new JPanel(new BorderLayout(8,12));
        JPanel labels = new JPanel(new GridLayout(2,1,0,6)); title.setFont(L10n.font(Font.BOLD,25));
        metadata.setForeground(MUTED); labels.add(title); labels.add(metadata); detail.add(labels,BorderLayout.NORTH);
        JScrollPane canvas = new JScrollPane(preview); canvas.setBorder(BorderFactory.createEmptyBorder()); canvas.getViewport().setBackground(BG);
        detail.add(canvas,BorderLayout.CENTER);
        JPanel lower = new JPanel(); lower.setLayout(new BoxLayout(lower,BoxLayout.Y_AXIS));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT,8,8));
        for (Component c : new Component[]{rotate,grid,bright,reset}) controls.add(c); lower.add(controls);
        rotate.addActionListener(e -> { rotation=(rotation+1)%4; updatePreview(); });
        reset.addActionListener(e -> { rotation=0; zoom.setValue(16); grid.setSelected(false); bright.setSelected(false); updatePreview(); });
        grid.addActionListener(e -> preview.repaint()); bright.addActionListener(e -> preview.repaint());
        JPanel z = new JPanel(new BorderLayout(12,0)); z.add(zoomLabel,BorderLayout.WEST); z.add(zoom,BorderLayout.CENTER); lower.add(z);
        zoom.addChangeListener(e -> { zoomLabel.setText(t("zoom",zoom.getValue())); updatePreview(); });
        itemId.setEditable(false); path.setEditable(false);
        JPanel info = new JPanel(new GridLayout(2,1,0,8)); info.setBorder(BorderFactory.createEmptyBorder(12,0,8,0));
        info.add(itemId); info.add(path); lower.add(info);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT,8,4)); actions.add(copy); actions.add(export); actions.add(craft); lower.add(actions);
        craft.setForeground(GREEN);craft.addActionListener(e->{if(selected!=null)RecipeDialog.show(root,recipePanel());});
        copy.addActionListener(e -> { if (selected!=null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected.id()),null); setStatus("status.copied",selected.id());
        }}); export.addActionListener(e -> exportPNG()); detail.add(lower,BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,library,detail); split.setResizeWeight(.30); split.setBorder(null);
        root.add(split,BorderLayout.CENTER); status.setForeground(MUTED); root.add(status,BorderLayout.SOUTH);
        applyLanguage(); filter();
    }
    void applyLanguage() {
        for(Object key:Collections.list(UIManager.getDefaults().keys()))if(key.toString().endsWith(".font"))UIManager.put(key,L10n.font(Font.PLAIN,14));
        translateFonts(root);
        language.setFont(L10n.cjkFont(Font.PLAIN,14));
        languageLabel.setText(t("language")); load.setText(t("open")); rotate.setText(t("rotate")); reset.setText(t("reset"));
        grid.setText(t("grid")); bright.setText(t("bright")); copy.setText(t("copy")); export.setText(t("export")); details.setText(t("details")); craft.setText(t("recipe.button"));
        hint.setText(t("hint")); zoomLabel.setText(t("zoom",zoom.getValue()));
        var border=BorderFactory.createTitledBorder(t("search.title")); border.setTitleColor(MUTED); filters.setBorder(border);
        search.setToolTipText(t("search.tip")); search.getAccessibleContext().setAccessibleName(t("search.title"));
        mode.setToolTipText(t("mode.tip")); itemId.setToolTipText(t("id.tip")); path.setToolTipText(t("path.tip"));
        UIManager.put("OptionPane.yesButtonText",t("yes")); UIManager.put("OptionPane.noButtonText",t("no"));
        UIManager.put("OptionPane.okButtonText",t("ok")); UIManager.put("OptionPane.cancelButtonText",t("cancel"));
        mode.repaint(); category.repaint(); filter();
        status.setText(t(statusKey,statusArgs));
        Window window=SwingUtilities.getWindowAncestor(root); if(window instanceof JFrame f) f.setTitle(t("app.title"));
        root.revalidate(); root.repaint();
    }
    static void translateFonts(Component c) {
        Font previous=c.getFont();if(previous!=null)c.setFont(L10n.font(previous.getStyle(),previous.getSize2D()));
        if(c instanceof Container container)for(Component child:container.getComponents())translateFonts(child);
    }
    void setStatus(String key,Object... args) { statusKey=key; statusArgs=args; status.setText(t(key,args)); }
    void filter() {
        Item previous=selected; int previousRotation=rotation;
        String query=search.getText().strip().toLowerCase(Locale.ROOT), cat=(String)category.getSelectedItem();
        model.clear();
        for(Item i:items) if (("Alle".equals(cat)||i.category().equals(cat)) &&
            (L10n.itemName(i)+" "+i.name()+" "+i.id()+" "+i.path()).toLowerCase(Locale.ROOT).contains(query)) model.addElement(i);
        if(!model.isEmpty()) { int index=previous==null?-1:model.indexOf(previous); list.setSelectedIndex(Math.max(0,index)); if(selected==previous)rotation=previousRotation; }
        else { selected=null; title.setText(t("empty.title")); metadata.setText(t("empty.hint")); itemId.setText(""); path.setText(""); preview.repaint(); }
        copy.setEnabled(selected!=null); export.setEnabled(selected!=null);craft.setEnabled(selected!=null);
        if(!busy) setStatus("status.count",model.size(),items.size());
    }
    void select(Item item) {
        selected=item; rotation=0; title.setText(L10n.itemName(item)); title.setToolTipText(L10n.itemName(item));
        metadata.setText(t("metadata",t("category."+item.category()),item.image().getWidth(),item.image().getHeight()));
        itemId.setText(item.id()); itemId.setCaretPosition(0); path.setText(item.path()); path.setCaretPosition(0); updatePreview();
    }
    void updatePreview() { preview.revalidate(); preview.repaint(); }
    RecipeDialog.Panel recipePanel(){return new RecipeDialog.Panel(selected,items,vanillaRecipes.withImported(report==null?List.of():report.recipes()),selected.category().equals("Import"));}
    JFileChooser chooser(boolean save) {
        // Use our own translated labels so every dialog works even when the OS locale differs.
        String[][] labels={{"lookInLabelText","file.lookIn"},{"saveInLabelText","file.saveIn"},{"fileNameLabelText","file.name"},
            {"filesOfTypeLabelText","file.type"},{"cancelButtonText","cancel"},{"openButtonText","open"},{"saveButtonText","save"},
            {"upFolderToolTipText","file.up"},{"homeFolderToolTipText","file.home"},{"newFolderToolTipText","file.newFolder"},
            {"listViewButtonToolTipText","file.list"},{"detailsViewButtonToolTipText","file.details"},{"acceptAllFileFilterText","file.all"},
            {"fileNameHeaderText","file.name"},{"fileSizeHeaderText","file.size"},{"fileTypeHeaderText","file.type"},{"fileDateHeaderText","file.date"},
            {"openButtonToolTipText","open"},{"saveButtonToolTipText","save"},{"cancelButtonToolTipText","cancel"},
            {"newFolderDialogText","file.newFolder"},{"newFolderErrorText","file.folderError"}};
        for(String[] label:labels) UIManager.put("FileChooser."+label[0],t(label[1]));
        JFileChooser fc=new JFileChooser(); fc.setDialogTitle(t(save?"export.title":"open.title"));
        fc.setApproveButtonText(t(save?"save":"open")); fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(t(save?"file.png":"file.packages"),
            save?new String[]{"png"}:new String[]{"mcaddon","mcpack","zip","jar","png"}));
        return fc;
    }
    void chooseImport() {
        JFileChooser fc=chooser(false); if(fc.showOpenDialog(root)!=JFileChooser.APPROVE_OPTION)return;
        startImport(fc.getSelectedFile().toPath(),mode.getSelectedIndex()==1);
    }
    void startImport(Path file,boolean all) {
        busy=true; load.setEnabled(false); mode.setEnabled(false); details.setEnabled(false); setStatus("status.loading",file.getFileName());
        new SwingWorker<ModTextureImporter.Report,Void>() {
            protected ModTextureImporter.Report doInBackground() throws Exception { return ModTextureImporter.load(file,all); }
            protected void done() {
                busy=false; load.setEnabled(true); mode.setEnabled(true);
                try {
                    report=get(); items.removeIf(i->i.category().equals("Import")); items.addAll(report.items());
                    search.setText(""); category.setSelectedItem("Import"); filter();
                    setStatus("status.loaded",report.items().size(),file.getFileName(),report.notices().size());
                } catch(Exception e) {
                    Throwable cause=e.getCause()==null?e:e.getCause(); setStatus("status.failed");
                    JOptionPane.showMessageDialog(root,errorText(cause),t("error.title"),JOptionPane.ERROR_MESSAGE);
                }
                details.setEnabled(report!=null);
            }
        }.execute();
    }
    static String errorText(Throwable e) {
        return e instanceof ModTextureImporter.ImportFailure f?t(f.key):t("error.read")+"\n"+e.getMessage();
    }
    void showDetails() {
        if(report==null)return;
        StringBuilder text=new StringBuilder(t("report.summary",report.items().size(),report.archives(),report.notices().size()));
        for(var n:report.notices()) text.append("\n\n").append(t(n.key(),n.detail()));
        JTextArea area=new JTextArea(text.toString(),15,72); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setCaretPosition(0);
        JOptionPane.showMessageDialog(root,new JScrollPane(area),t("details"),JOptionPane.INFORMATION_MESSAGE);
    }
    void exportPNG() {
        if(selected==null)return;
        JFileChooser fc=chooser(true); fc.setSelectedFile(new File(selected.path().substring(selected.path().lastIndexOf('/')+1)));
        if(fc.showSaveDialog(root)!=JFileChooser.APPROVE_OPTION)return;
        File out=fc.getSelectedFile(); if(!out.getName().toLowerCase(Locale.ROOT).endsWith(".png"))out=new File(out+".png");
        if(out.exists()&&JOptionPane.showConfirmDialog(root,t("replace.question"),t("export.title"),JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        try { ImageIO.write(selected.image(),"png",out); setStatus("status.saved",out.getName()); }
        catch(IOException e) { JOptionPane.showMessageDialog(root,errorText(e),t("error.title"),JOptionPane.ERROR_MESSAGE); }
    }
    static List<Item> builtins() throws IOException {
        List<Item> result=new ArrayList<>();
        Map<String,BufferedImage> cache=new HashMap<>();
        Set<String> ids=new HashSet<>();
        try(InputStream in=resource("catalog.tsv")) {
            for(String row:new String(in.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                if(row.isBlank())continue; String[] p=row.split("\t");
                if(p.length!=5||!ids.add(p[0]))throw new IOException("Invalid or duplicate catalog entry");
                BufferedImage image=cache.get(p[3]);
                if(image==null){try(InputStream png=resource(p[3])) {image=decode(png.readAllBytes());cache.put(p[3],image);}}
                result.add(new Item(p[0],p[1],p[2],p[4],image));
            }
        } return result;
    }
    static InputStream resource(String name) throws IOException {
        InputStream in=BedrockItemViewer.class.getResourceAsStream("/"+name); return in!=null?in:Files.newInputStream(Path.of(name));
    }
    static BufferedImage decode(byte[] data) throws IOException {
        if(data.length<8 || data[0]!=(byte)137 || data[1]!='P' || data[2]!='N' || data[3]!='G')throw new IOException("PNG signature");
        try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(in); if(!readers.hasNext())throw new IOException("PNG decoder");
            ImageReader reader=readers.next();
            try { reader.setInput(in); int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||w>2048||h>2048||(long)w*h>4_194_304)throw new IOException("PNG dimensions"); return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
    static String pretty(String path) {
        String name=path.substring(path.lastIndexOf('/')+1).replaceFirst("(?i)\\.png$","").replace('_',' ');
        return name.isEmpty()?"PNG":Character.toUpperCase(name.charAt(0))+name.substring(1);
    }
    static List<Item> importFile(Path path) throws IOException { return ModTextureImporter.load(path,false).items(); }
    final class Preview extends JComponent {
        public Dimension getPreferredSize() {
            if(selected==null)return new Dimension(400,320); int w=selected.image().getWidth(),h=selected.image().getHeight();
            return new Dimension(Math.max(400,(rotation%2==0?w:h)*zoom.getValue()+64),Math.max(320,(rotation%2==0?h:w)*zoom.getValue()+64));
        }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();
            Color a=bright.isSelected()?new Color(222,229,225):new Color(23,33,38), b=bright.isSelected()?new Color(203,214,209):new Color(29,41,45);
            g.setColor(a); g.fillRect(0,0,getWidth(),getHeight());
            for(int y=0;y<getHeight();y+=20)for(int x=0;x<getWidth();x+=20)if((x/20+y/20)%2==0){g.setColor(b);g.fillRect(x,y,20,20);}
            if(selected!=null){
                int s=zoom.getValue(),w=selected.image().getWidth(),h=selected.image().getHeight();
                g.translate(getWidth()/2.,getHeight()/2.); g.rotate(rotation*Math.PI/2);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(selected.image(),-w*s/2,-h*s/2,w*s,h*s,null);
                if(grid.isSelected()&&s>=4){g.setColor(new Color(160,210,190,95));
                    for(int x=0;x<=w;x++)g.drawLine(-w*s/2+x*s,-h*s/2,-w*s/2+x*s,h*s/2);
                    for(int y=0;y<=h;y++)g.drawLine(-w*s/2,-h*s/2+y*s,w*s/2,-h*s/2+y*s);
                }
            }g.dispose();
        }
    }
    static class ItemRenderer extends DefaultListCellRenderer {
        final Map<BufferedImage,ImageIcon> thumbnails=new WeakHashMap<>();
        ImageIcon thumbnail(BufferedImage source){
            return thumbnails.computeIfAbsent(source,image->{
                BufferedImage thumb=new BufferedImage(40,40,BufferedImage.TYPE_INT_ARGB);
                Graphics2D g=thumb.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                double scale=Math.min(40.0/image.getWidth(),40.0/image.getHeight());
                int w=Math.max(1,(int)Math.round(image.getWidth()*scale)),h=Math.max(1,(int)Math.round(image.getHeight()*scale));
                g.drawImage(image,(40-w)/2,(40-h)/2,w,h,null);g.dispose();return new ImageIcon(thumb);
            });
        }
        public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean active,boolean focus){
            super.getListCellRendererComponent(list,value,index,active,focus);Item i=(Item)value;
            setBorder(BorderFactory.createEmptyBorder(8,12,8,12));setIconTextGap(12);setIcon(thumbnail(i.image()));
            setText(L10n.itemName(i));setToolTipText(L10n.itemName(i)+" · "+i.id());setForeground(active?GREEN:TEXT);setBackground(active?new Color(37,66,54):PANEL);return this;
        }
    }
    static class KeyRenderer extends DefaultListCellRenderer {
        public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean active,boolean focus){return super.getListCellRendererComponent(list,t(String.valueOf(value)),index,active,focus);}
    }
    static class CategoryRenderer extends DefaultListCellRenderer {
        public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean active,boolean focus){return super.getListCellRendererComponent(list,t("category."+value),index,active,focus);}
    }
    static void theme(){
        for(String type:List.of("Panel","List","TextField","Button","ComboBox","CheckBox","Slider","ScrollPane","SplitPane","OptionPane","TextArea"))UIManager.put(type+".background",type.equals("List")||type.equals("TextField")?PANEL:BG);
        for(String type:List.of("Label","List","TextField","Button","ComboBox","CheckBox","TextArea"))UIManager.put(type+".foreground",TEXT);
        UIManager.put("TextField.caretForeground",GREEN);UIManager.put("OptionPane.messageForeground",TEXT);
        for(Object key:Collections.list(UIManager.getDefaults().keys()))if(key.toString().endsWith(".font"))UIManager.put(key,L10n.font(Font.PLAIN,14));
    }
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container container)layout(container);}
    public static void main(String[] args)throws Exception{
        L10n.loadSettings();
        if(args.length==1&&args[0].equals("--check")){System.out.println("OK: "+builtins().size()+" built-in items; "+RecipeBook.vanilla().all.size()+" recipes; 8 languages; default English");return;}
        if(args.length==2&&args[0].equals("--recipes")){
            var found=RecipeBook.vanilla().find(args[1]);for(var recipe:found)System.out.println(recipe.identifier()+"\t"+recipe.kind()+"\t"+recipe.stations()+"\t"+recipe.totals()+"\t"+recipe.results());System.out.println("TOTAL\t"+found.size());return;
        }
        if(args.length>=2&&args[0].equals("--list")){
            var report=ModTextureImporter.load(Path.of(args[1]),args.length>2&&args[2].equals("--all"));
            for(Item i:report.items())System.out.println(i.path()+"\t"+i.image().getWidth()+"x"+i.image().getHeight()+"\t"+i.id());
            System.out.println("TOTAL\t"+report.items().size()+"\tNOTICES\t"+report.notices().size()+"\tARCHIVES\t"+report.archives());return;
        }
        theme();
        if(args.length>=4&&args[0].equals("--recipe-screenshot")){
            for(var lang:L10n.Language.values())if(lang.code.equals(args[3]))L10n.language=lang;theme();
            ModTextureImporter.Report imported=args.length>=5?ModTextureImporter.load(Path.of(args[4]),false):null;
            SwingUtilities.invokeAndWait(()->{try{
                BedrockItemViewer app=new BedrockItemViewer();if(imported!=null){app.items.addAll(imported.items());app.report=imported;}
                app.select(app.items.stream().filter(i->i.id().equals(args[2])).findFirst().orElseThrow());
                RecipeDialog.Panel panel=app.recipePanel();panel.setSize(1040,780);layout(panel);
                BufferedImage image=new BufferedImage(1040,780,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(image,"png",new File(args[1]));
            }catch(IOException e){throw new UncheckedIOException(e);}});return;
        }
        if(args.length>=2&&args[0].equals("--screenshot")){
            if(args.length>=3)for(var lang:L10n.Language.values())if(lang.code.equals(args[2]))L10n.language=lang;
            ModTextureImporter.Report imported=args.length>=4?ModTextureImporter.load(Path.of(args[3]),false):null;
            SwingUtilities.invokeAndWait(()->{try{
                BedrockItemViewer app=new BedrockItemViewer();if(imported!=null){app.items.addAll(imported.items());app.report=imported;app.details.setEnabled(true);app.category.setSelectedItem("Import");app.filter();}
                app.root.setSize(1280,850);layout(app.root);BufferedImage image=new BufferedImage(1280,850,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();app.root.printAll(g);g.dispose();ImageIO.write(image,"png",new File(args[1]));
            }catch(IOException e){throw new UncheckedIOException(e);}});return;
        }
        SwingUtilities.invokeLater(()->{try{
            BedrockItemViewer app=new BedrockItemViewer();JFrame frame=new JFrame(t("app.title"));frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setContentPane(app.root);frame.setMinimumSize(new Dimension(1200,780));frame.setSize(1280,850);frame.setLocationRelativeTo(null);frame.setVisible(true);
        }catch(IOException e){JOptionPane.showMessageDialog(null,errorText(e),t("error.title"),JOptionPane.ERROR_MESSAGE);}});
    }
}
