"""
Project Structure and Full Content Extraction Script

This script scans the entire project directory under 'app/src/main/' and extracts relevant information
from all Kotlin (.kt) and XML (.xml) files. The extracted information includes:

1. **Project Structure**: A detailed representation of all directories and files, providing an overview
   of the entire structure.

2. **Kotlin and XML Files**: Full content extraction of relevant Kotlin (.kt) and XML (.xml) files.
   Additionally, the script performs a search for relevant elements such as class definitions, function signatures, and
   key XML tags to provide a comprehensive understanding of the codebase.

The output of this script is intended to be detailed, easy to read, and suitable for sharing directly
in a chat, enabling efficient collaboration without manually navigating the entire project.

Functions:
- extract_full_content_from_kotlin(file_path): Extracts the full content from Kotlin files and highlights class and function definitions.
- extract_full_content_from_xml(file_path): Extracts the full content from XML files and highlights key XML tags.
- scan_project_structure(directory): Scans the directory and compiles the project structure and full content of relevant files.
- main(): The main function to execute the extraction and print the results.

Usage:
- Run this script in the root directory of your project.
- The script will output the project structure along with the full content of Kotlin and XML files, highlighting relevant elements.

Note:
- This script is designed for small to medium-sized projects where an overview of the structure and
  key elements is needed for quick reference or discussion.
"""

import os
import re

def extract_full_content_from_kotlin(file_path):
    """
    Extracts the full content from a Kotlin file and highlights class and function definitions.

    Args:
        file_path (str): The path to the Kotlin file.

    Returns:
        dict: A dictionary containing the full content and highlighted elements (classes and functions).
    """
    with open(file_path, 'r', encoding='utf-8') as file:
        content = file.read()
        # Find class and function definitions
        classes = re.findall(r'class\s+\w+\s*(\(.*\))?\s*{', content)
        functions = re.findall(r'fun\s+\w+\s*\(.*\)\s*{', content)
        return {
            "full_content": content,
            "classes": classes,
            "functions": functions
        }

def extract_full_content_from_xml(file_path):
    """
    Extracts the full content from an XML file and highlights key XML tags.

    Args:
        file_path (str): The path to the XML file.

    Returns:
        dict: A dictionary containing the full content and highlighted elements (key XML tags).
    """
    with open(file_path, 'r', encoding='utf-8') as file:
        content = file.read()
        # Extract key XML tags (excluding comments and empty lines)
        key_tags = [line.strip() for line in content.splitlines() if re.search(r'<[^!?].*?>', line.strip())]
        return {
            "full_content": content,
            "key_tags": key_tags
        }

def scan_project_structure(directory):
    """
    Scans the directory and compiles the project structure and full content of relevant files.

    Args:
        directory (str): The root directory to scan.

    Returns:
        tuple: A tuple containing the project structure as a list and the extracted content as a dictionary.
    """
    extracted_info = {}
    project_structure = []

    for root, _, files in os.walk(directory):
        # Store the project structure
        level = root.replace(directory, '').count(os.sep)
        indent = ' ' * 4 * level
        project_structure.append(f"{indent}{os.path.basename(root)}/")

        for file in files:
            if file.endswith('.kt') or file.endswith('.xml'):
                file_path = os.path.join(root, file)
                # Extract full content based on file type
                if file.endswith('.kt'):
                    relevant_info = extract_full_content_from_kotlin(file_path)
                elif file.endswith('.xml'):
                    relevant_info = extract_full_content_from_xml(file_path)

                if relevant_info:
                    extracted_info[file_path] = relevant_info

                # Add file to project structure
                sub_indent = ' ' * 4 * (level + 1)
                project_structure.append(f"{sub_indent}{file}")

    return project_structure, extracted_info

def main():
    """
    Main function to execute the project structure and content extraction.
    Outputs the project structure and full content of relevant files.
    """
    project_directory = "app/src/main/"  # Directory of your project
    project_structure, extracted_info = scan_project_structure(project_directory)

    # Print the project structure
    print("Project Structure:")
    for line in project_structure:
        print(line)
    print("\n")

    # Print the extracted full content and highlighted elements
    print("Extracted Full Content and Highlights:")
    for file_path, info in extracted_info.items():
        print(f"File: {file_path}")
        print("Full Content:")
        print(info["full_content"])
        print("\nHighlighted Elements:")
        if "classes" in info:
            for cls in info["classes"]:
                print(f"  Class: {cls}")
        if "functions" in info:
            for func in info["functions"]:
                print(f"  Function: {func}")
        if "key_tags" in info:
            for tag in info["key_tags"]:
                print(f"  XML Tag: {tag}")
        print("\n")

if __name__ == "__main__":
    main()