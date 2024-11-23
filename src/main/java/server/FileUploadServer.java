package server;

import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class FileUploadServer extends NanoHTTPD {
    private static final String UPLOAD_DIR = "uploads";
    private final CustomTempFileManager tempFileManager;

    public FileUploadServer() {
        super(9000);
        new File(UPLOAD_DIR).mkdirs();
        tempFileManager = new CustomTempFileManager();
        setTempFileManagerFactory(() -> tempFileManager);
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        Response response = null;

        try {
            if ("/upload".equals(session.getUri()) && Method.POST.equals(session.getMethod())) {
                session.parseBody(files);
                response = handleFileUpload(session, files);
            } else if (Method.GET.equals(session.getMethod())) {
                String uri = session.getUri();
                if (uri.startsWith("/download")) {
                    response = downloadFile(session);
                } else {
                    response = listFiles();
                }
            } else if (Method.DELETE.equals(session.getMethod())) {
                response = deleteFile(session);
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Endpoint not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: " + e.getMessage());
        } finally {
            // Cleanup temporary files after response is prepared
            cleanupTempFiles(files);
        }

        return response;
    }

    private void cleanupTempFiles(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        files.values().forEach(tempFilePath -> {
            if (tempFilePath == null) {
                return;
            }

            Path path = Paths.get(tempFilePath);
            try {
                // Wait briefly to ensure file handles are released
                Thread.sleep(100);

                // Try to release any system resources
                System.gc();

                // First try with Files.delete()
                Files.deleteIfExists(path);
            } catch (IOException | InterruptedException e) {
                try {
                    // Second attempt with traditional File.delete()
                    File tempFile = path.toFile();
                    if (tempFile.exists() && !tempFile.delete()) {
                        // If still can't delete, schedule for deletion on JVM exit
                        tempFile.deleteOnExit();
                        System.err.println("WARNING: Could not delete temp file: " + tempFile.getAbsolutePath() +
                                ". Scheduled for deletion on JVM exit.");
                    }
                } catch (Exception ex) {
                    System.err.println("ERROR: Failed to cleanup temp file: " + tempFilePath);
                    ex.printStackTrace();
                }
            }
        });
    }

    private Response handleFileUpload(IHTTPSession session, Map<String, String> files) {
        if (files.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file provided");
        }

        String tempFilePath = files.values().iterator().next();
        Path tempPath = Paths.get(tempFilePath);

        try {
            // Retrieve original file name
            String originalFileName = session.getParameters().get("file").get(0);
            Path destinationPath = Paths.get(UPLOAD_DIR, originalFileName);

            // Create upload directory if it doesn't exist
            Files.createDirectories(destinationPath.getParent());

            // Move temp file to the destination
            Files.move(tempPath, destinationPath, StandardCopyOption.REPLACE_EXISTING);

            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT,
                    "File uploaded successfully: " + originalFileName);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Failed to upload file: " + e.getMessage());
        }
    }

    private Response downloadFile(IHTTPSession session) {
        if (session == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT, "Invalid session");
        }

        Map<String, List<String>> params = session.getParameters();
        if (params == null || !params.containsKey("file") || params.get("file") == null || params.get("file").isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT, "Missing file parameter");
        }

        String filename = params.get("file").get(0);
        if (filename == null || filename.trim().isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT, "Invalid filename");
        }

        // Sanitize filename to prevent directory traversal
        filename = new File(filename).getName();
        File file = new File(UPLOAD_DIR, filename);

        // Verify file is within UPLOAD_DIR
        try {
            if (!file.getCanonicalPath().startsWith(new File(UPLOAD_DIR).getCanonicalPath())) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN,
                        MIME_PLAINTEXT, "Access denied");
            }
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Error validating file path");
        }

        if (!file.exists() || !file.isFile()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT, "File not found: " + filename);
        }

        try {
            // Get MIME type
            String mimeType = getMimeType(filename);

            // Read file into byte array
            byte[] fileData = Files.readAllBytes(file.toPath());

            // Create response with file content
            Response response = newFixedLengthResponse(Response.Status.OK,
                    mimeType,
                    new ByteArrayInputStream(fileData),
                    fileData.length);

            // Set headers for download
            response.addHeader("Content-Disposition",
                    "attachment; filename=\"" + filename + "\"");
            response.addHeader("Content-Transfer-Encoding", "binary");
            response.addHeader("Cache-Control", "no-cache");

            return response;

        } catch (IOException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Failed to read file: " + e.getMessage());
        }
    }

    // Helper method to determine MIME type
    private String getMimeType(String filename) {
        String mimeType = "application/octet-stream";

        if (filename.toLowerCase().endsWith(".pdf")) {
            mimeType = "application/pdf";
        } else if (filename.toLowerCase().endsWith(".txt")) {
            mimeType = "text/plain";
        } else if (filename.toLowerCase().endsWith(".html") || filename.toLowerCase().endsWith(".htm")) {
            mimeType = "text/html";
        } else if (filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")) {
            mimeType = "image/jpeg";
        } else if (filename.toLowerCase().endsWith(".png")) {
            mimeType = "image/png";
        } else if (filename.toLowerCase().endsWith(".gif")) {
            mimeType = "image/gif";
        } else if (filename.toLowerCase().endsWith(".doc") || filename.toLowerCase().endsWith(".docx")) {
            mimeType = "application/msword";
        } else if (filename.toLowerCase().endsWith(".xls") || filename.toLowerCase().endsWith(".xlsx")) {
            mimeType = "application/vnd.ms-excel";
        } else if (filename.toLowerCase().endsWith(".zip")) {
            mimeType = "application/zip";
        }

        return mimeType;
    }

    @Override
    public void stop() {
        try {
            // First, stop accepting new requests
            super.stop();

            // Give some time for ongoing uploads to complete
            Thread.sleep(2000);

            // Perform final cleanup
            tempFileManager.clear();

            // Shutdown cleanup executor
            tempFileManager.shutdown();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private Response deleteFile(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();

        // Extract the filename parameter from the request
        String fileName = params.get("file") != null ? params.get("file").get(0) : null;

        if (fileName == null || fileName.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT, "Filename is required");
        }

        // Create the file object from the uploaded directory
        File file = new File(UPLOAD_DIR + File.separator + fileName);

        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT, "File not found");
        }

        // Try to delete the file
        if (file.delete()) {
            return newFixedLengthResponse(Response.Status.OK,
                    MIME_PLAINTEXT, "File deleted successfully");
        } else {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Failed to delete file");
        }
    }

    private Response listFiles() {
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists() || !uploadDir.isDirectory()) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Upload directory not found");
        }

        // List files in the directory
        File[] files = uploadDir.listFiles();
        if (files == null || files.length == 0) {
            return newFixedLengthResponse(Response.Status.OK,
                    MIME_PLAINTEXT, "No files available");
        }

        StringBuilder responseContent = new StringBuilder("Files:\n");
        for (File file : files) {
            responseContent.append(file.getName()).append("\n");
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, responseContent.toString());
    }



    private void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }

    private void clearDirectory() {
        File uploadDir = new File(UPLOAD_DIR);
        if (uploadDir.exists() && uploadDir.isDirectory()) {
            File[] files = uploadDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        boolean deleted = file.delete();
                        if (deleted) {
                            System.out.println("Deleted: " + file.getName());
                        } else {
                            System.out.println("Failed to delete: " + file.getName());
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        new File(UPLOAD_DIR).delete();
        try {
            FileUploadServer server = new FileUploadServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            System.out.println("Server started on http://localhost:9000");
            System.out.println("Upload files using POST request to http://localhost:9000/upload");
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
        }
    }
}





