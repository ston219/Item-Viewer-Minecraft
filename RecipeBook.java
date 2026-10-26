import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline recipe metadata. This class never runs game, pack or mod code. */
final class RecipeBook {
    enum Kind { SHAPED, SHAPELESS, FURNACE, SMITHING, BREWING, TRIM }
    record Ingredient(String id,String tag,int data,int count,List<Ingredient> alternatives) {
        boolean empty(){return id.isEmpty()&&tag.isEmpty()&&alternatives.isEmpty();}
        Ingredient withCount(int value){return new Ingredient(id,tag,data,value,alternatives);}
        String identity(){return id+"@"+data+"#"+tag+alternatives;}
    }
    record Recipe(String identifier,Kind kind,List<String> stations,List<Ingredient> slots,List<Ingredient> results,String source) {
        int[] bounds(){
            int left=3,top=3,right=-1,bottom=-1;
            for(int index=0;index<slots.size();index++)if(!slots.get(index).empty()){
                left=Math.min(left,index%3);right=Math.max(right,index%3);top=Math.min(top,index/3);bottom=Math.max(bottom,index/3);
            }
            return new int[]{left,top,right,bottom};
        }
        boolean canCraftInInventory(){
            if(stations.stream().noneMatch(s->s.equals("crafting_table")||s.equals("minecraft:crafting_table")))return false;
            if(slots.stream().anyMatch(i->i.count()>1))return false;
            if(kind==Kind.SHAPELESS)return slots.size()<=4;
            if(kind!=Kind.SHAPED)return false;
            int[] b=bounds();return b[2]>=0&&b[2]-b[0]<2&&b[3]-b[1]<2;
        }
        int gridSize(){return canCraftInInventory()?2:3;}
        List<Ingredient> displayedGrid(){
            if(!canCraftInInventory()||kind!=Kind.SHAPED)return slots;
            int[] b=bounds();List<Ingredient> result=new ArrayList<>();
            for(int row=0;row<2;row++)for(int col=0;col<2;col++){
                int x=b[0]+col,y=b[1]+row;
                result.add(x<=b[2]&&y<=b[3]?slots.get(y*3+x):EMPTY);
            }
            return List.copyOf(result);
        }
        List<Ingredient> totals(){
            Map<String,Ingredient> totals=new LinkedHashMap<>();
            for(Ingredient ingredient:slots)if(!ingredient.empty()){
                Ingredient previous=totals.get(ingredient.identity());
                totals.put(ingredient.identity(),ingredient.withCount(ingredient.count()+(previous==null?0:previous.count())));
            }
            return List.copyOf(totals.values());
        }
    }
    static final Ingredient EMPTY=new Ingredient("","",0,0,List.of());
    static final Map<String,String> aliases=loadAliases();
    final List<Recipe> all;
    final Map<String,List<Recipe>> index=new HashMap<>();
    RecipeBook(List<Recipe> recipes){
        all=List.copyOf(recipes);
        for(Recipe recipe:all)for(Ingredient result:recipe.results())if(!result.id().isEmpty()){
            String id=result.id().startsWith("minecraft:potion_type:")?"minecraft:potion":result.id();
            List<Recipe> list=index.computeIfAbsent(id,k->new ArrayList<>());if(!list.contains(recipe))list.add(recipe);
        }
    }
    static Map<String,String> loadAliases(){
        Map<String,String> result=new HashMap<>();
        try(InputStream in=BedrockItemViewer.resource("recipes/aliases.tsv")){
            for(String row:new String(in.readAllBytes(),StandardCharsets.UTF_8).split("\\R")){
                if(row.isBlank())continue;String[] fields=row.split("\t");result.put(fields[0],fields[1]);
            }
        }catch(IOException e){throw new UncheckedIOException(e);}
        return Map.copyOf(result);
    }
    static RecipeBook vanilla()throws IOException{
        List<Recipe> recipes=new ArrayList<>();
        for(String resource:List.of("recipes/vanilla.json","recipes/documented.json"))try(InputStream in=BedrockItemViewer.resource(resource)){
            Object parsed=ModTextureImporter.Json.parse(in.readAllBytes());
            if(!(parsed instanceof List<?> list))throw new IOException("Invalid recipe catalog");
            for(Object entry:list){Map<?,?> row=map(entry);recipes.addAll(parseObject(row.get("definition"),text(row.get("source"))));}
        }
        return new RecipeBook(recipes);
    }
    RecipeBook withImported(List<Recipe> imported){
        if(imported.isEmpty())return this;
        Set<String> replaced=new HashSet<>();for(Recipe recipe:imported)replaced.add(recipe.identifier());
        List<Recipe> merged=new ArrayList<>();
        for(Recipe recipe:all)if(!replaced.contains(recipe.identifier()))merged.add(recipe);
        merged.addAll(imported);
        return new RecipeBook(merged);
    }
    List<Recipe> find(String id){
        LinkedHashSet<Recipe> matches=new LinkedHashSet<>();
        for(String alias:id.split("\\s*\\|\\s*"))matches.addAll(index.getOrDefault(canonical(alias),List.of()));
        return List.copyOf(matches);
    }
    static String canonical(String id){id=qualified(id);return aliases.getOrDefault(id,id);}
    static String qualified(String id){return id.isEmpty()||id.contains(":")?id:"minecraft:"+id;}
    static Map<?,?> map(Object value){return value instanceof Map<?,?> m?m:Map.of();}
    static String text(Object value){return value instanceof String s?s:"";}
    static int number(Object value,int fallback)throws IOException{
        if(value==null)return fallback;
        if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw new IOException("Invalid recipe number");
        return n.intValue();
    }
    static Ingredient ingredient(Object value)throws IOException{
        if(value==null)return EMPTY;
        if(value instanceof List<?> options){
            if(options.isEmpty()||options.size()>64)throw new IOException("Invalid recipe alternatives");
            List<Ingredient> choices=new ArrayList<>();for(Object option:options)choices.add(ingredient(option));
            Ingredient first=choices.get(0);return new Ingredient(first.id(),first.tag(),first.data(),first.count(),List.copyOf(choices));
        }
        Map<?,?> object=map(value);
        String id=value instanceof String s?s:text(object.containsKey("item")?object.get("item"):object.get("id"));
        String tag=text(object.get("tag"));
        if(id.startsWith("#")){tag=id.substring(1);id="";}
        int data=number(object.get("data"),0),count=number(object.get("count"),1);
        if(count<1||count>65536)throw new IOException("Invalid recipe count");
        if(!id.isEmpty()){
            // Bedrock also accepts legacy item IDs carrying a numeric data suffix.
            if(id.matches(".+:[-]?[0-9]+$")){
                int colon=id.lastIndexOf(':');data=Integer.parseInt(id.substring(colon+1));id=id.substring(0,colon);
            }
            id=qualified(id);
            String resolved=aliases.get(id+"@"+data);
            if(resolved!=null){id=resolved;data=0;}else id=aliases.getOrDefault(id,id);
        }
        if(id.isEmpty()&&tag.isEmpty())throw new IOException("Recipe ingredient has no item or tag");
        return new Ingredient(id,qualified(tag),data,count,List.of());
    }
    static List<Recipe> parse(byte[] json,String source)throws IOException{return parseObject(ModTextureImporter.Json.parse(json),source);}
    static List<Recipe> parseObject(Object value,String source)throws IOException{
        Map<?,?> root=map(value);List<Recipe> result=new ArrayList<>();
        for(var entry:root.entrySet())if(String.valueOf(entry.getKey()).startsWith("minecraft:recipe_")){
            String type=String.valueOf(entry.getKey()).substring("minecraft:recipe_".length());
            Recipe recipe=parseDefinition(type,map(entry.getValue()),source,false);
            if(recipe!=null)result.add(recipe);
        }
        if(root.containsKey("type")){
            String type=text(root.get("type"));
            if(type.startsWith("minecraft:")){
                type=type.substring(10);
                type=switch(type){case "crafting_shaped"->"shaped";case "crafting_shapeless"->"shapeless";
                    case "smelting","blasting","smoking","campfire_cooking"->"furnace";case "stonecutting"->"stonecutting";default->type;};
                Recipe recipe=parseDefinition(type,root,source,true);if(recipe!=null)result.add(recipe);
            }
        }
        return List.copyOf(result);
    }
    static Recipe parseDefinition(String type,Map<?,?> data,String source,boolean java)throws IOException{
        Kind kind=switch(type){case "shaped"->Kind.SHAPED;case "shapeless","stonecutting"->Kind.SHAPELESS;
            case "furnace"->Kind.FURNACE;case "smithing_transform"->Kind.SMITHING;
            case "brewing_mix","brewing_container"->Kind.BREWING;case "smithing_trim"->Kind.TRIM;default->null;};
        if(kind==null)return null;
        String id=text(map(data.get("description")).get("identifier"));
        if(id.isEmpty())id=source.replace('\\','/').replaceFirst("(?i)\\.json$","");
        List<String> stations=new ArrayList<>();
        if(data.get("tags") instanceof List<?> tags)for(Object tag:tags)if(tag instanceof String s)stations.add(s);
        if(java){
            String raw=text(data.get("type"));stations.add(switch(raw){case "minecraft:blasting"->"blast_furnace";case "minecraft:smoking"->"smoker";case "minecraft:campfire_cooking"->"campfire";default->switch(kind){case FURNACE->"furnace";case SMITHING,TRIM->"smithing_table";case BREWING->"brewing_stand";default->type.equals("stonecutting")?"stonecutter":"crafting_table";};});
        }
        if(stations.isEmpty())stations.add(switch(kind){case FURNACE->"furnace";case SMITHING,TRIM->"smithing_table";case BREWING->"brewing_stand";default->"crafting_table";});
        List<Ingredient> slots=new ArrayList<>();
        if(kind==Kind.SHAPED){
            if(!(data.get("pattern") instanceof List<?> pattern)||pattern.isEmpty()||pattern.size()>3)throw new IOException("Invalid crafting pattern");
            int width=0;for(Object row:pattern){if(!(row instanceof String s)||s.isEmpty()||s.length()>3)throw new IOException("Invalid crafting row");width=Math.max(width,s.length());}
            Map<?,?> keys=map(data.get("key"));
            for(int row=0;row<3;row++)for(int col=0;col<3;col++){
                int x=col-(3-width)/2;
                String line=row<pattern.size()?text(pattern.get(row)):"";
                char symbol=x>=0&&x<line.length()?line.charAt(x):' ';
                if(symbol==' ')slots.add(EMPTY);else{Object key=keys.get(String.valueOf(symbol));if(key==null)throw new IOException("Undefined recipe symbol");slots.add(ingredient(key));}
            }
        }else if(kind==Kind.SHAPELESS){
            Object ingredients=type.equals("stonecutting")?data.get("ingredient"):data.get("ingredients");
            if(ingredients instanceof List<?> list){if(list.isEmpty()||list.size()>9)throw new IOException("Invalid ingredient count");for(Object entry:list)slots.add(ingredient(entry));}
            else if(ingredients!=null)slots.add(ingredient(ingredients));else throw new IOException("No recipe ingredients");
        }else if(kind==Kind.FURNACE)slots.add(ingredient(data.containsKey("input")?data.get("input"):data.get("ingredient")));
        else if(kind==Kind.BREWING){slots.add(ingredient(data.get("input")));slots.add(ingredient(data.get("reagent")));}
        else {slots.add(ingredient(data.get("template")));slots.add(ingredient(data.get("base")));slots.add(ingredient(data.get("addition")));}
        if(slots.stream().allMatch(Ingredient::empty))throw new IOException("Empty recipe");
        Object output=data.containsKey("output")?data.get("output"):data.get("result");
        List<Ingredient> results=new ArrayList<>();
        if(kind==Kind.TRIM&&output==null)results.add(new Ingredient("","minecraft:trimmable_armors",0,1,List.of()));
        else if(output instanceof List<?> list){if(list.isEmpty()||list.size()>64)throw new IOException("Invalid recipe outputs");for(Object entry:list)results.add(ingredient(entry));}
        else if(java&&type.equals("stonecutting")&&output instanceof String){
            Ingredient produced=ingredient(output);int count=number(data.get("count"),1);if(count<1||count>65536)throw new IOException("Invalid result count");results.add(produced.withCount(count));
        }else results.add(ingredient(output));
        if(results.stream().anyMatch(Ingredient::empty))throw new IOException("Missing recipe output");
        return new Recipe(id,kind,List.copyOf(stations),List.copyOf(slots),List.copyOf(results),source);
    }
}
