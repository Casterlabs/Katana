FROM eclipse-temurin:21-jre-ubi9-minimal
WORKDIR /home/container

LABEL org.opencontainers.image.source="https://github.com/casterlabs/katana"

# code
COPY ./docker/* /home/container
COPY ./target/Katana.jar /home/container
RUN chmod +x docker_launch.sh
RUN mkdir data

# entrypoint
CMD [ "./docker_launch.sh" ]
EXPOSE 81/tcp
EXPOSE 80/tcp
EXPOSE 443/tcp
