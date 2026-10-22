# Cloud-Based Disaster Information Management System

A full-stack educational project for recording disaster incidents, tracking their status, and viewing response information.

## Technology
- Frontend: HTML, CSS, JavaScript
- Backend: Java 17+ with built-in `HttpServer`
- Storage: local JSON file (`data/incidents.json`)
- Dependencies: none

## Features
- Add disaster reports with type, severity, location, description, and contact name
- View, search, and filter incident reports
- Update an incident's response status
- Delete a report
- Summary cards for total, critical, and active incidents
- Responsive interface

## Run locally
Requires Java JDK 17 or newer.

### Windows
Open Command Prompt in this project folder, then run:
```bat
javac --add-modules jdk.httpserver -d out src\main\java\DisasterInfoServer.java
java --add-modules jdk.httpserver -cp out DisasterInfoServer
```

### macOS / Linux
Open a terminal in this project folder, then run:
```bash
javac --add-modules jdk.httpserver -d out src/main/java/DisasterInfoServer.java
java --add-modules jdk.httpserver -cp out DisasterInfoServer
```

Open http://localhost:8080 in your browser. Stop the server with Ctrl+C.

The server creates `data/incidents.json` on first use. The data file is ignored by Git so local reports are not accidentally uploaded.

## API
- `GET /api/incidents` — list incidents
- `POST /api/incidents` — create an incident
- `PATCH /api/incidents?id=INCIDENT_ID` — update status
- `DELETE /api/incidents?id=INCIDENT_ID` — delete an incident

## Important deployment note
This is a local demonstration, not a production emergency-response system. Do not enter sensitive personal information. For real deployment, use authenticated access, authorization, encrypted transport, backups, audit logs, validation, and a managed database. Do not rely on this demo for emergency dispatch or life-safety decisions.

## Upload to GitHub
1. Create a new repository.
2. Extract the ZIP.
3. Upload the extracted files and folders using **Add file → Upload files**.
4. Commit the changes.

The project source can be hosted on GitHub, but the Java backend must run on a Java-capable host to be accessible online.
