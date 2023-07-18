#!/usr/bin/ruby
project_id = "dash-wallet-firebase"
key_file   = ".deploy/gc-storage-service-account.json"
bucket_name = "dash-wallet-firebase.appspot.com"
flavor = ARGV[0]
file_name = "explore/explore.db"
if flavor == "prod"
  puts "Downloading the production database"
else
  file_name = "explore/explore-testnet.db"
  puts "Downloading the test database"
end

assets_path = "wallet/assets/"
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
target_file_name = "explore/explore.db"
target_file_path = "#{assets_path}#{target_file_name}"

# create output file
# out_file = File.new(target_file_path, "w")
# out_file.close

file.download target_file_path

puts "Downloaded #{file.name} to #{target_file_path}"
