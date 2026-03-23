FROM maven:3.9.6-eclipse-temurin-17

WORKDIR /app

# משיכת הקוד מגיטהאב
RUN git clone https://github.com/mps435/NFmp3Downloader.git .

# התקנת תלויות ושרת רשת
RUN apt-get update && apt-get install -y \
    ffmpeg curl python3 xvfb nginx \
    libxtst6 libxrender1 libxi6 libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

# הכנת התיקייה
RUN mkdir -p /root/.config/NFmp3Downloader/bin/

# >>> תיקון שגיאת ה-DNS של יוטיוב (כפיית IPv4) <<<
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /root/.config/NFmp3Downloader/bin/yt-dlp-real && \
    chmod a+rx /root/.config/NFmp3Downloader/bin/yt-dlp-real
RUN echo '#!/bin/bash' > /root/.config/NFmp3Downloader/bin/yt-dlp.exe && \
    echo '/root/.config/NFmp3Downloader/bin/yt-dlp-real --force-ipv4 "$@"' >> /root/.config/NFmp3Downloader/bin/yt-dlp.exe && \
    chmod +x /root/.config/NFmp3Downloader/bin/yt-dlp.exe

# העתקת ffmpeg
RUN cp /usr/bin/ffmpeg /root/.config/NFmp3Downloader/bin/ffmpeg.exe && \
    chmod +x /root/.config/NFmp3Downloader/bin/ffmpeg.exe

# >>> מניעת כיבוי השרת בעת רענון הדף (התיקון לשגיאת הבנייה!) <<<
# במקום לשבור את התחביר, מחליפים את פקודות הכיבוי בפקודות חוקיות שלא עושות נזק
RUN find src/main/java/ -type f -name "*.java" -exec sed -i 's/System.exit/System.out.print/g' {} + && \
    find src/main/java/ -type f -name "*.java" -exec sed -i 's/\.stop()/.toString()/g' {} +

# >>> תיקון חיבור ה-Websockets לענן ומניעת קפיצת הפרוטוקול במחשב <<<
RUN sed -i "s|nfmp3downloader://start|javascript:void(0)|g" src/main/resources/web/index.html && \
    sed -i "s|'ws://localhost:9595|'wss://' + window.location.host + '|g" src/main/resources/web/index.html && \
    sed -i "s|'http://localhost:9595|window.location.protocol + '//' + window.location.host + '|g" src/main/resources/web/index.html

# בניית הפרויקט
RUN mvn clean compile

# הגדרת Nginx לתמיכה בחיבור חי (WebSockets)
# הוספתי כאן גם הגדרת Timeout כדי שהחיבור לא יתנתק בהורדות ארוכות
RUN printf 'events {}\n\
http {\n\
    include /etc/nginx/mime.types;\n\
    server {\n\
        listen 7860;\n\
        location / {\n\
            root /app/target/classes/web;\n\
            index index.html;\n\
            try_files $uri $uri/ @backend;\n\
        }\n\
        location @backend {\n\
            proxy_pass http://127.0.0.1:9595;\n\
            proxy_http_version 1.1;\n\
            proxy_set_header Upgrade $http_upgrade;\n\
            proxy_set_header Connection "upgrade";\n\
            proxy_set_header Host $host;\n\
            proxy_read_timeout 3600;\n\
        }\n\
    }\n\
}\n' > /etc/nginx/nginx.conf

# סקריפט הפעלה משולב
RUN printf '#!/bin/bash\n\
nginx &\n\
xvfb-run -a mvn exec:java -Dexec.mainClass="com.mps.App"\n' > /start.sh && \
    chmod +x /start.sh

ENV PORT=7860
EXPOSE 7860

CMD ["/start.sh"]
