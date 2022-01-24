#!/usr/bin/ruby
project_id = "dash-wallet-firebase"
key_file   = ".deploy/gc-storage-service-account.json"
bucket_name = "explore-dash-sync"
file_name = "explore.dat"
local_file_path = "wallet/assets/explore.dat"
require "google/cloud/storage"

# Explicitly use service account credentials by specifying the private key
# file.
storage = Google::Cloud::Storage.new project: project_id, keyfile: key_file

# Make an authenticated API request
storage.buckets.each do |bucket|
  puts bucket.name
end

bucket = storage.bucket bucket_name, skip_lookup: true
file = bucket.file file_name

file.download local_file_path

puts "Downloaded #{file.name} to #{local_file_path}"