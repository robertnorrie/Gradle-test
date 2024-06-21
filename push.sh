#!/bin/bash

# Stage all modified files
git add .

# Commit with a prompt for your message
git commit -m "Dylan Fixing Issues in Gradle"

# Push your changes to the remote branch
git push origin $(git branch --show-current)
