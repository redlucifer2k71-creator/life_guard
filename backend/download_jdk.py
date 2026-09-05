import os
import urllib.request
import zipfile

JDK_URL = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip"
TARGET_DIR = r"C:\Users\Subin Karthick C\.jdks"
ZIP_PATH = os.path.join(TARGET_DIR, "jdk17.zip")

print(f"Creating directory: {TARGET_DIR}")
os.makedirs(TARGET_DIR, exist_ok=True)

print("Downloading OpenJDK 17...")
urllib.request.urlretrieve(JDK_URL, ZIP_PATH)

print("Extracting OpenJDK 17...")
with zipfile.ZipFile(ZIP_PATH, 'r') as zip_ref:
    zip_ref.extractall(TARGET_DIR)

if os.path.exists(ZIP_PATH):
    os.remove(ZIP_PATH)

print("✅ OpenJDK 17 installed successfully!")
