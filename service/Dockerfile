FROM node:24-slim
WORKDIR /app
COPY package.json ./
COPY src/ ./src/
COPY public/ ./public/
ENV HOST=0.0.0.0
ENV NODE_ENV=production
EXPOSE 8787
CMD ["node", "src/server.mjs"]
