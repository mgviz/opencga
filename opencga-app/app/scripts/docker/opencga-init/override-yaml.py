import oyaml as yaml
import argparse
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--config-path", help="path to the configuration.yml file", default="/opt/opencga/conf/configuration.yml")
parser.add_argument("--client-config-path", help="path to the client-configuration.yml file", default="/opt/opencga/conf/client-configuration.yml")
parser.add_argument("--storage-config-path", help="path to the storage-configuration.yml file", default="/opt/opencga/conf/storage-configuration.yml")
parser.add_argument("--search-hosts", required=True)
parser.add_argument("--clinical-hosts", required=True)
parser.add_argument("--cellbase-mongo-hosts", required=False, help="A CSV list of mongodb hosts which are running the cellbase database")
parser.add_argument("--cellbase-mongo-hosts-password", required=False, help="The password for the cellbase mongo server provided in '--cellbase-mongo-hosts'")
parser.add_argument("--cellbase-mongo-hosts-user", required=False, help="The username for the cellbase mongo server provided in '--cellbase-mongo-hosts'")
parser.add_argument("--cellbase-rest-urls", required=False, help="A CSV list of cellbase rest servers hosting the cellbase service")
parser.add_argument("--catalog-database-hosts", required=True)
parser.add_argument("--catalog-database-user", required=True)
parser.add_argument("--catalog-database-password", required=True)
parser.add_argument("--catalog-search-hosts", required=True)
parser.add_argument("--catalog-search-user", required=True)
parser.add_argument("--catalog-search-password", required=True)
parser.add_argument("--rest-host", required=True)
parser.add_argument("--grpc-host", required=True)
parser.add_argument("--batch-execution-mode", required=True)
parser.add_argument("--batch-account-name", required=True)
parser.add_argument("--batch-account-key", required=True)
parser.add_argument("--batch-endpoint", required=True)
parser.add_argument("--batch-pool-id", required=True)
parser.add_argument("--batch-docker-args", required=True)
parser.add_argument("--batch-docker-image", required=True)
parser.add_argument("--batch-max-concurrent-jobs", required=True)
parser.add_argument("--hadoop-ssh-host", required=False)
parser.add_argument("--hadoop-ssh-user", required=False)
parser.add_argument("--hadoop-ssh-password", required=False)
parser.add_argument("--hadoop-ssh-remote-opencga-home", required=False)
parser.add_argument("--health-check-interval", required=True)
parser.add_argument("--save", help="save update to source configuration files (default: false)", default=False, action='store_true')
args = parser.parse_args()

##############################################################################################################
# Load storage configuration yaml
##############################################################################################################

with open(args.storage_config_path) as f:
    storage_config = yaml.safe_load(f)

# Inject search hosts
search_hosts = args.search_hosts.replace('\"','').split(",")
for i, search_host in enumerate(search_hosts):
    if i == 0:
        # If we are overriding the default hosts,
        # clear them only on the first iteration
        storage_config["search"]["hosts"].clear()
    storage_config["search"]["hosts"].insert(i, search_host.strip())

# Inject clinical hosts
clinical_hosts = args.clinical_hosts.replace('\"','').split(",")
for i, clinical_host in enumerate(clinical_hosts):
    if i == 0:
        # If we are overriding the default hosts,
        # clear them only on the first iteration
        storage_config["clinical"]["hosts"].clear()
    storage_config["clinical"]["hosts"].insert(i, clinical_host.strip())

# Inject cellbase database
has_cellbase_mongo_hosts = args.cellbase_mongo_hosts is not None and args.cellbase_mongo_hosts != ""

if has_cellbase_mongo_hosts:
    cellbase_mongo_hosts = args.cellbase_mongo_hosts.replace('\"','').split(",")
    for i, cellbase_mongo_host in enumerate(cellbase_mongo_hosts):
        if i == 0:
            # If we are overriding the default hosts,
            # clear them only on the first iteration
            storage_config["cellbase"]["database"]["hosts"].clear()
        storage_config["cellbase"]["database"]["hosts"].insert(i, cellbase_mongo_host.strip())

    storage_config["cellbase"]["database"]["options"]["authenticationDatabase"] = "admin"
    storage_config["cellbase"]["database"]["options"]["sslEnabled"] = True
    storage_config["cellbase"]["database"]["user"] = args.cellbase_mongo_hosts_user
    storage_config["cellbase"]["database"]["password"] = args.cellbase_mongo_hosts_password
    storage_config["cellbase"]["preferred"] = "local"

# Inject cellbase rest host, if set
if args.cellbase_rest_urls is not None and args.cellbase_rest_urls != "":
    cellbase_rest_urls = args.cellbase_rest_urls.replace('\"', '').split(",")
    if len(cellbase_rest_urls) > 0:
        for i, cellbase_url in enumerate(cellbase_rest_urls):
            if i == 0:
                # If we are overriding the default hosts,
                # clear them only on the first iteration
                storage_config["cellbase"]["hosts"].clear()
            storage_config["cellbase"]["hosts"].insert(i, cellbase_url.strip())


# Inject Hadoop ssh configuration
if args.hadoop_ssh_host and args.hadoop_ssh_user and args.hadoop_ssh_password and args.hadoop_ssh_remote_opencga_home:
    for _, storage_engine in enumerate(storage_config["storageEngines"]):
        # If we have cellbase hosts set the annotator to the DB Adaptor
        if has_cellbase_mongo_hosts:
            storage_engine["variant"]["options"]["annotator"] = "cellbase_db_adaptor"

        if storage_engine["id"] == "hadoop": 
            storage_engine["variant"]["options"]["opencga.mr.executor"] = "ssh"
            storage_engine["variant"]["options"]["opencga.mr.executor.ssh.host"] = args.hadoop_ssh_host
            storage_engine["variant"]["options"]["opencga.mr.executor.ssh.user"] = args.hadoop_ssh_user
            storage_engine["variant"]["options"]["opencga.mr.executor.ssh.password"] = args.hadoop_ssh_password
            #storage_engine["variant"]["options"]["opencga.mr.executor.ssh.key"] = args.hadoop_ssh_key # TODO instead of password
            storage_engine["variant"]["options"]["opencga.mr.executor.ssh.remote_opencga_home"] = args.hadoop_ssh_remote_opencga_home

##############################################################################################################
# Load configuration yaml
##############################################################################################################

with open(args.config_path) as f:
    config = yaml.safe_load(f)

# Inject catalog database
catalog_hosts = args.catalog_database_hosts.replace('\"','').split(",")
for i, catalog_host in enumerate(catalog_hosts):
    if i == 0:
        # If we are overriding the default hosts,
        # clear them only on the first iteration
        config["catalog"]["database"]["hosts"].clear()
    config["catalog"]["database"]["hosts"].insert(i, catalog_host.strip())

config["catalog"]["database"]["user"] = args.catalog_database_user
config["catalog"]["database"]["password"] = args.catalog_database_password
config["catalog"]["database"]["options"]["enableSSL"] = True
config["catalog"]["database"]["options"]["authenticationDatabase"] = "admin"

# Inject search database
catalog_search_hosts = args.catalog_search_hosts.replace('\"','').split(",")
for i, catalog_search_host in enumerate(catalog_search_hosts):
    if i == 0:
        # If we are overriding the default hosts,
        # clear them only on the first iteration
        config["catalog"]["search"]["hosts"].clear()
    config["catalog"]["search"]["hosts"].insert(i, catalog_search_host.strip())
config["catalog"]["search"]["user"] = args.catalog_search_user
config["catalog"]["search"]["password"] = args.catalog_search_password

# Inject execution settings
config["execution"]["mode"] = args.batch_execution_mode
config["execution"]["maxConcurrentIndexJobs"] = int(args.batch_max_concurrent_jobs)
config["execution"]["options"] = {}
config["execution"]["options"]["batchAccount"] = args.batch_account_name
config["execution"]["options"]["batchKey"] = args.batch_account_key
config["execution"]["options"]["batchUri"] = args.batch_endpoint
config["execution"]["options"]["batchPoolId"] = args.batch_pool_id
config["execution"]["options"]["dockerImageName"] = args.batch_docker_image
config["execution"]["options"]["dockerArgs"] = args.batch_docker_args

# Inject healthCheck interval
config["healthCheck"]["interval"] = args.health_check_interval

##############################################################################################################
# Load client configuration yaml
##############################################################################################################

with open(args.client_config_path) as f:
    client_config = yaml.safe_load(f)

# Inject grpc and rest host
client_config["rest"]["host"] = args.rest_host.replace('"','')
client_config["grpc"]["host"] = args.grpc_host.replace('"','')

# Running with --save will update the configuration files inplace.
# Without --save will simply dump the update YAML to stdout so that
# the caller can handle it.
# Note: The dump will use the safe representation so there is likely
# to be format diffs between the original input and the output as well
# as value changes.
if args.save == False:
    yaml.dump(storage_config, sys.stdout, default_flow_style=False, allow_unicode=True)
    print("---")  # Add yaml delimiter
    yaml.dump(config, sys.stdout, default_flow_style=False, allow_unicode=True)
    print("---")  # Add yaml delimiter
    yaml.dump(client_config, sys.stdout, default_flow_style=False, allow_unicode=True)
else:
    with open(args.storage_config_path, "w") as f:
        yaml.dump(storage_config, f, default_flow_style=False)
    with open(args.config_path, "w") as f:
        yaml.dump(config, f, default_flow_style=False)
    with open(args.client_config_path, "w") as f:
        yaml.dump(client_config, f, default_flow_style=False)

