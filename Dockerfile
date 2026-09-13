FROM node:24-alpine

WORKDIR /app
ENV NODE_ENV=production

RUN apk add --no-cache su-exec && mkdir -p /data && chown node:node /data

COPY --chown=node:node service/package.json ./package.json
COPY --chown=node:node service/src ./src
COPY --chown=node:node service/public ./public
COPY docker-entrypoint.sh /usr/local/bin/minelatino-entrypoint

RUN chmod 0755 /usr/local/bin/minelatino-entrypoint

ENTRYPOINT ["minelatino-entrypoint"]
CMD ["node", "src/server.mjs"]
