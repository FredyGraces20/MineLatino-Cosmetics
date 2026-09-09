FROM node:24-alpine

WORKDIR /app
ENV NODE_ENV=production

COPY service/package.json ./package.json
COPY service/src ./src
COPY service/public ./public

CMD ["node", "src/server.mjs"]
