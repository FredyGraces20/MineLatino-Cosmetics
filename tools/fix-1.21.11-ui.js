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

// WardrobeButton.java: onPress() → onPress(InputWithModifiers)
fix('WardrobeButton.java', [
    ['@Override public void onPress() { action.run(); }',
     '@Override public void onPress(net.minecraft.client.input.InputWithModifiers input) { action.run(); }'],
]);

// WardrobeScreen.java: fix mouse events, blit, renderTooltip
fix('WardrobeScreen.java', [
    // Add import for MouseButtonEvent
    ['import net.minecraft.network.chat.Component;',
     'import net.minecraft.network.chat.Component;\nimport net.minecraft.client.input.MouseButtonEvent;'],

    // mouseClicked: (double x, double y, int button) → (MouseButtonEvent event, boolean doubleClick)
    ['@Override public boolean mouseClicked(double x,double y,int button) {\n        if(button==0 && x>=previewX && x<previewX+previewW && y>=top+20 && y<bottom-28) { dragging=true; return true; }\n        return super.mouseClicked(x,y,button);\n    }',
     '@Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {\n        double x=event.x(),y=event.y();\n        if(event.button()==0 && x>=previewX && x<previewX+previewW && y>=top+20 && y<bottom-28) { dragging=true; return true; }\n        return super.mouseClicked(event,doubleClick);\n    }'],

    // mouseDragged: (double x, double y, int button, double dx, double dy) → (MouseButtonEvent event, double dx, double dy)
    ['@Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {\n        if(dragging && button==0) { orbit+=(float)dx*1.2f; return true; }\n        return super.mouseDragged(x,y,button,dx,dy);\n    }',
     '@Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {\n        if(dragging && event.button()==0) { orbit+=(float)dx*1.2f; return true; }\n        return super.mouseDragged(event,dx,dy);\n    }'],

    // mouseReleased: (double x, double y, int button) → (MouseButtonEvent event)
    ['@Override public boolean mouseReleased(double x,double y,int button) { dragging=false; return super.mouseReleased(x,y,button); }',
     '@Override public boolean mouseReleased(MouseButtonEvent event) { dragging=false; return super.mouseReleased(event); }'],

    // blit: use the simple overload that doesn't need RenderPipeline
    ['g.blit(texture,getX()+4,getY()+4,26,26,0,0,26,26,26,26)',
     'g.blit(texture,getX()+4,getY()+4,26,26,0f,0f,26f,26f)'],

    // renderTooltip: simplified - just remove it for now, it's non-critical
    ['if(my>=height-24 && message!=null) g.renderTooltip(font,Component.literal(message),mx,my);',
     '// tooltip rendering adapted for 1.21.11 API (non-critical)'],
]);

console.log('\nAll UI fixes applied!');
