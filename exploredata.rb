#!/usr/bin/ruby
project_id = "dash-wallet-firebase"
key_file   = ".deploy/gc-storage-service-account.json"
bucket_name = "dash-wallet-firebase.appspot.com"
file_name = "explore/explore.dat"
local_file_path = "wallet/assets/explore/"
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
timestamp = file.updated_at.strftime("%Q")

file.download "#{local_file_path}#{timestamp}.dat"

puts "Downloaded #{file.name} to #{local_file_path}"