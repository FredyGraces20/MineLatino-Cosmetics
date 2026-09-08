const fs = require('fs');
const path = require('path');

const base = path.join(__dirname, '..', 'client-1.21.11', 'src', 'main', 'java', 'com', 'minelatino', 'cosmetics', 'client');

function fix(relPath, replacements) {
    const filePath = path.join(base, relPath);
    let content = fs.readFileSync(filePath, 'utf8');
    for (const [old, newStr] of replacements) {
        if (typeof old === 'string') {
            content = content.split(old).join(newStr);
        } else {
            content = content.replace(old, newStr);
        }
    }
    fs.writeFileSync(filePath, content, 'utf8');
    console.log('Fixed:', relPath);
}

// 1. CosmeticsClient.java - GameProfile.getId() → id()
fix('CosmeticsClient.java', [
    ['player.getGameProfile().getId()', 'player.getGameProfile().id()'],
    ['p.getGameProfile().getId()', 'p.getGameProfile().id()'],
]);

// 2. CosmeticPreview.java - remove setFilter, fix getFrameTime
fix('CosmeticPreview.java', [
    // Remove the setFilter line entirely
    [/mc\.getTextureManager\(\)\.getTexture\(actor\.getSkin\(\)\.body\(\)\.texturePath\(\)\)\.setFilter\(false, false\);\n/, ''],
    ['mc.getFrameTime()', '0f'],
]);

// 3. ResourceCache.java - remove setFilter calls
fix('ResourceCache.java', [
    [/Minecraft\.getInstance\(\)\.getTextureManager\(\)\.getTexture\(texLoc\)\.setFilter\(false, false\);\n/, ''],
    [/Minecraft\.getInstance\(\)\.getTextureManager\(\)\.getTexture\(location\)\.setFilter\(false,false\);\n/, ''],
]);

// 4. WardrobeButton.java - renderWidget → renderContents
fix('WardrobeButton.java', [
    ['protected void renderWidget(GuiGraphics g,int mx,int my,float delta)', 'protected void renderContents(GuiGraphics g,int mx,int my,float delta)'],
]);

// 5. WardrobeScreen.java - renderWidget → renderContents
fix('WardrobeScreen.java', [
    ['@Override protected void renderWidget(GuiGraphics g,int mx,int my,float delta) {', '@Override protected void renderContents(GuiGraphics g,int mx,int my,float delta) {'],
]);

// 6. PlayerRendererMixin.java - GameProfile.getId() → id()
fix(path.join('mixin', 'PlayerRendererMixin.java'), [
    ['player.getGameProfile().getId()', 'player.getGameProfile().id()'],
]);

// 7. AuthManager.java - getMinecraftSessionService() → services().sessionService()
fix('AuthManager.java', [
    ['Minecraft.getInstance().getMinecraftSessionService()', 'Minecraft.getInstance().services().sessionService()'],
]);

console.log('\nAll fixes applied!');
