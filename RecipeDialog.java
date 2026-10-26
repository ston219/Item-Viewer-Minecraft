import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

final class RecipeDialog {
    static String t(String key,Object... args){return L10n.t(key,args);}
    static String shortId(String id){return id.startsWith("minecraft:")?id.substring(10):id;}
    static String human(String id){String s=shortId(id).replace('_',' ');return s.isEmpty()?"?":Character.toUpperCase(s.charAt(0))+s.substring(1);}
    static String translated(String key,String fallback){String value=t(key);return value.equals(key)?fallback:value;}
    static String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static JLabel label(String value,int style,int size){JLabel label=new JLabel(value);label.putClientProperty("html.disable",true);label.setFont(L10n.font(style,size));return label;}
    static final Map<String,String> representatives=Map.ofEntries(
        Map.entry("minecraft:planks","minecraft:oak_planks"),Map.entry("minecraft:wooden_slabs","minecraft:oak_slab"),
        Map.entry("minecraft:stone_tool_materials","minecraft:cobblestone"),Map.entry("minecraft:stone_crafting_materials","minecraft:cobblestone"),
        Map.entry("minecraft:coals","minecraft:coal"),Map.entry("minecraft:mushrooms_for_stew","minecraft:red_mushroom"),
        Map.entry("minecraft:soul_fire_base_blocks","minecraft:soul_sand"),Map.entry("minecraft:egg","minecraft:egg"),
        Map.entry("minecraft:logs","minecraft:oak_log"),Map.entry("minecraft:logs_that_burn","minecraft:oak_log"),
        Map.entry("minecraft:metal_nuggets","minecraft:iron_nugget"),Map.entry("minecraft:wool","minecraft:white_wool"),
        Map.entry("minecraft:trim_templates","minecraft:coast_armor_trim_smithing_template"),Map.entry("minecraft:trim_materials","minecraft:iron_ingot"),
        Map.entry("minecraft:trimmable_armors","minecraft:iron_chestplate"),Map.entry("minecraft:is_pickaxe","minecraft:iron_pickaxe"));
    static final String[] colors="white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split(" ");

    static final class Panel extends JPanel {
        final BedrockItemViewer.Item target;
        final Map<String,BedrockItemViewer.Item> items=new HashMap<>();
        final Map<String,ImageIcon> icons=new HashMap<>();
        final RecipeBook book;
        final List<RecipeBook.Recipe> matches;
        final JComboBox<RecipeBook.Recipe> choices;
        final JPanel content=new JPanel(new BorderLayout(0,14));
        final boolean imported;
        RecipeBook.Recipe current;
        Panel(BedrockItemViewer.Item target,List<BedrockItemViewer.Item> library,RecipeBook book,boolean imported){
            super(new BorderLayout(16,16));this.target=target;this.book=book;this.imported=imported;
            for(var item:library)items.put(item.id(),item);
            matches=book.find(target.id());choices=new JComboBox<>(matches.toArray(RecipeBook.Recipe[]::new));
            setBorder(BorderFactory.createEmptyBorder(22,24,20,24));
            JPanel heading=new JPanel();heading.setLayout(new BoxLayout(heading,BoxLayout.Y_AXIS));
            JLabel title=label(t("recipe.title",L10n.itemName(target)),Font.BOLD,24);title.setToolTipText(title.getText());title.setForeground(BedrockItemViewer.GREEN);title.setAlignmentX(0);heading.add(title);
            heading.add(Box.createVerticalStrut(12));
            if(!matches.isEmpty()){
                JPanel row=new JPanel(new BorderLayout(12,0));row.add(label(t("recipe.choose"),Font.PLAIN,14),BorderLayout.WEST);
                choices.setRenderer(new DefaultListCellRenderer(){
                    public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                        super.getListCellRendererComponent(list,value,index,selected,focus);putClientProperty("html.disable",true);
                        if(value instanceof RecipeBook.Recipe r){int number=matches.indexOf(r)+1;RecipeBook.Ingredient result=r.results().get(0);
                            setText(number+" / "+matches.size()+" · "+station(r)+" · "+result.count()+" × "+name(result));}
                        return this;
                    }
                });row.add(choices,BorderLayout.CENTER);row.setAlignmentX(0);heading.add(row);
                choices.addActionListener(e->refresh());
            }
            add(heading,BorderLayout.NORTH);add(content,BorderLayout.CENTER);
            JLabel footer=label(t("recipe.data"),Font.PLAIN,12);footer.setForeground(BedrockItemViewer.MUTED);add(footer,BorderLayout.SOUTH);
            refresh();
        }
        String name(RecipeBook.Ingredient ingredient){
            if(ingredient.empty())return "";
            if(!ingredient.alternatives().isEmpty())return String.join(" / ",ingredient.alternatives().stream().map(this::name).toList());
            if(!ingredient.tag().isEmpty())return t("recipe.any_tag",translated("recipe.tag."+ingredient.tag(),human(ingredient.tag())));
            String variant=translated("recipe.variant."+ingredient.id()+"@"+ingredient.data(),"");
            if(!variant.isEmpty())return variant;
            if(ingredient.id().startsWith("minecraft:potion_type:")){
                String effect=ingredient.id().substring("minecraft:potion_type:".length());
                boolean extended=effect.startsWith("long_"),strong=effect.startsWith("strong_");
                effect=effect.replaceFirst("^(long_|strong_)","");
                effect=switch(effect){case "nightvision"->"nightVision";case "invisibility"->"invisibility";case "leaping"->"jump";case "fire_resistance"->"fireResistance";
                    case "swiftness"->"moveSpeed";case "slowness"->"moveSlowdown";case "water_breathing"->"waterBreathing";case "healing"->"heal";case "harming"->"harm";
                    case "strength"->"damageBoost";case "weakness"->"weakness";case "turtle_master"->"turtleMaster";case "slow_falling"->"slowFalling";
                    case "water"->"emptyPotion";default->effect;};
                String base=translated("recipe.potion."+effect+".name",human(effect));
                return extended?t("recipe.extended",base):strong?t("recipe.strong",base):base;
            }
            BedrockItemViewer.Item known=items.get(ingredient.id());
            String base=known==null?translated("item."+ingredient.id(),human(ingredient.id())):L10n.itemName(known);
            return ingredient.data()!=0?t("recipe.variant",base,ingredient.data()):base;
        }
        BufferedImage image(RecipeBook.Ingredient ingredient){
            if(ingredient.id().equals("minecraft:bed")&&ingredient.data()>=0&&ingredient.data()<colors.length){
                String path="assets/recipe-variants/bed_"+colors[ingredient.data()]+".png";
                try(InputStream in=BedrockItemViewer.resource(path)){return BedrockItemViewer.decode(in.readAllBytes());}catch(IOException ignored){}
            }
            String id=ingredient.tag().isEmpty()?ingredient.id():representatives.getOrDefault(ingredient.tag(),"");
            if(id.startsWith("minecraft:potion_type:"))id="minecraft:potion";
            BedrockItemViewer.Item known=items.get(id);return known==null?null:known.image();
        }
        Icon icon(RecipeBook.Ingredient ingredient){
            if(ingredient.empty())return null;
            String key=ingredient.identity();ImageIcon cached=icons.get(key);if(cached!=null)return cached;
            BufferedImage image=image(ingredient);if(image==null)return null;
            BufferedImage thumb=new BufferedImage(44,44,BufferedImage.TYPE_INT_ARGB);Graphics2D g=thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale=Math.min(44.0/image.getWidth(),44.0/image.getHeight());int w=Math.max(1,(int)Math.round(image.getWidth()*scale)),h=Math.max(1,(int)Math.round(image.getHeight()*scale));
            g.drawImage(image,(44-w)/2,(44-h)/2,w,h,null);g.dispose();ImageIcon result=new ImageIcon(thumb);icons.put(key,result);return result;
        }
        JPanel cell(RecipeBook.Ingredient ingredient,boolean output){
            JPanel cell=new JPanel(new BorderLayout());cell.setPreferredSize(new Dimension(output?84:68,output?84:68));
            cell.setBackground(output?new Color(36,64,49):BedrockItemViewer.PANEL);
            cell.setBorder(BorderFactory.createLineBorder(output?BedrockItemViewer.GREEN:new Color(76,94,103),output?2:1));
            if(!ingredient.empty()){
                JLabel sprite=label("",Font.BOLD,18);sprite.setHorizontalAlignment(SwingConstants.CENTER);sprite.setIcon(icon(ingredient));
                if(sprite.getIcon()==null)sprite.setText(ingredient.tag().isEmpty()?"?":"#");cell.add(sprite,BorderLayout.CENTER);
                String badge=(ingredient.tag().isEmpty()?"":"# ")+(ingredient.count()>1?"×"+ingredient.count():"");
                if(!badge.isEmpty()){JLabel count=label(badge,Font.BOLD,12);count.setHorizontalAlignment(SwingConstants.RIGHT);cell.add(count,BorderLayout.SOUTH);}
                cell.setToolTipText("<html>"+html(ingredient.count()+" × "+name(ingredient))+(!ingredient.tag().isEmpty()?"<br>"+html(ingredient.tag()):"")+"</html>");
            }
            return cell;
        }
        String station(RecipeBook.Recipe recipe){
            if(recipe.canCraftInInventory())return t("recipe.station.inventory_or_table");
            return String.join(" / ",recipe.stations().stream().map(s->{String id=shortId(s);return translated("recipe.station."+id,translated("item.minecraft:"+id,human(id)));}).toList());
        }
        void refresh(){
            content.removeAll();current=(RecipeBook.Recipe)choices.getSelectedItem();
            if(current==null){
                JTextArea message=new JTextArea(t(imported?"recipe.none_import":"recipe.none"));message.setEditable(false);message.setLineWrap(true);message.setWrapStyleWord(true);message.setFont(L10n.font(Font.PLAIN,17));
                message.setBorder(BorderFactory.createEmptyBorder(40,16,16,16));content.add(message,BorderLayout.CENTER);
            }else{
                JPanel visual=new JPanel(new BorderLayout(0,10));
                JLabel method=label(station(current)+" · "+t("recipe.kind."+current.kind().name()),Font.BOLD,17);method.setHorizontalAlignment(SwingConstants.CENTER);visual.add(method,BorderLayout.NORTH);
                JPanel diagram=new JPanel(new FlowLayout(FlowLayout.CENTER,20,16));
                boolean crafting=current.stations().stream().anyMatch(s->shortId(s).equals("crafting_table"));
                if(current.kind()==RecipeBook.Kind.SHAPED||(current.kind()==RecipeBook.Kind.SHAPELESS&&crafting)){
                    int size=current.gridSize();List<RecipeBook.Ingredient> displayed=current.displayedGrid();
                    JPanel grid=new JPanel(new GridLayout(size,size,6,6));for(int slot=0;slot<size*size;slot++)grid.add(cell(slot<displayed.size()?displayed.get(slot):RecipeBook.EMPTY,false));diagram.add(grid);
                }else for(RecipeBook.Ingredient ingredient:current.slots()){
                    if(!ingredient.empty())diagram.add(cell(ingredient,false));
                }
                diagram.add(label("→",Font.BOLD,34));
                RecipeBook.Ingredient output=current.results().stream().filter(i->i.id().equals(target.id())).findFirst().orElse(current.results().get(0));
                JPanel produced=new JPanel(new BorderLayout(0,8));produced.add(cell(output,true),BorderLayout.CENTER);
                JLabel quantity=label(t("recipe.makes",output.count()),Font.BOLD,14);quantity.setHorizontalAlignment(SwingConstants.CENTER);produced.add(quantity,BorderLayout.SOUTH);diagram.add(produced);
                visual.add(diagram,BorderLayout.CENTER);
                JTextArea note=new JTextArea(note(current));note.setEditable(false);note.setLineWrap(true);note.setWrapStyleWord(true);note.setRows(2);note.setFont(L10n.font(Font.PLAIN,14));visual.add(note,BorderLayout.SOUTH);
                content.add(visual,BorderLayout.NORTH);
                DefaultListModel<RecipeBook.Ingredient> model=new DefaultListModel<>();current.totals().forEach(model::addElement);
                JList<RecipeBook.Ingredient> ingredients=new JList<>(model);ingredients.setFixedCellHeight(52);ingredients.setFixedCellWidth(760);
                ingredients.setCellRenderer(new DefaultListCellRenderer(){
                    public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                        super.getListCellRendererComponent(list,value,index,selected,focus);putClientProperty("html.disable",true);
                        RecipeBook.Ingredient ingredient=(RecipeBook.Ingredient)value;setText(ingredient.count()+" × "+name(ingredient));setIcon(icon(ingredient));setIconTextGap(12);setBorder(BorderFactory.createEmptyBorder(4,10,4,10));return this;
                    }
                });
                JScrollPane scroll=new JScrollPane(ingredients);var border=BorderFactory.createTitledBorder(t("recipe.ingredients"));border.setTitleColor(BedrockItemViewer.MUTED);scroll.setBorder(border);scroll.setPreferredSize(new Dimension(800,160));content.add(scroll,BorderLayout.CENTER);
                JLabel source=label(t(current.source().startsWith("import:")?"recipe.source_import":current.source().startsWith("documented:")?"recipe.source_documented":"recipe.source_vanilla"),Font.PLAIN,12);source.setForeground(BedrockItemViewer.MUTED);source.setToolTipText(current.source());content.add(source,BorderLayout.SOUTH);
            }
            content.revalidate();content.repaint();
        }
        String note(RecipeBook.Recipe recipe){
            return switch(recipe.kind()){
                case SHAPED->t("recipe.note_shaped");case SHAPELESS->t(recipe.stations().stream().anyMatch(s->shortId(s).equals("stonecutter"))?"recipe.note_stonecutter":"recipe.note_shapeless");
                case FURNACE->t("recipe.note_furnace");case SMITHING->t("recipe.note_smithing");case BREWING->t("recipe.note_brewing");case TRIM->t("recipe.note_trim");
            };
        }
    }
    static void show(Component parent,Panel panel){
        Window owner=SwingUtilities.getWindowAncestor(parent);JDialog dialog=new JDialog(owner,t("recipe.button"),Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane(panel);dialog.setMinimumSize(new Dimension(900,680));dialog.setSize(1040,780);dialog.setLocationRelativeTo(parent);dialog.setVisible(true);
    }
}
