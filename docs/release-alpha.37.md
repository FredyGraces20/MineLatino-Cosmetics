# MineLatino Cosmetics 0.1.0-alpha.37

- Aísla el render 3D del armario en su propio lote gráfico.
- Vacía los vértices del jugador y sus cosméticos mientras el recorte del preview sigue activo
  en 1.21.4, y aísla el framebuffer diferido de Minecraft 1.21.11 en su propio estrato.
- Evita que modelos grandes o animados se dibujen encima de categorías, botones y colección.
- Restaura el estado del recorte incluso si una capa de render externa falla.
