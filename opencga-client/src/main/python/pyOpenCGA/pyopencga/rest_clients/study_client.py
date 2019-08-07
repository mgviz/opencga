from pyopencga.rest_clients._parent_rest_clients import _ParentBasicCRUDClient, _ParentAclRestClient

class Studies(_ParentBasicCRUDClient, _ParentAclRestClient):
    """
    This class contains method for the Studies webservices
    """

    def __init__(self, configuration, session_id=None, login_handler=None, *args, **kwargs):
        _category = 'studies'
        super(Studies, self).__init__(configuration, _category, session_id, login_handler, *args, **kwargs)

    def aggregation_stats(self, study, **options):
        """
        Fetch catalog study stats
        URL: /{apiVersion}/studies/{studies}/aggregationStats

        :param studies: Comma separated list of studies [[user@]project:]study up to a maximum of 100
        :param default: calculate default stats (bool ['true' default,'false'])
        :param fileFields: list of file fields separated by semicolons, e.g.: studies;type. For nested fields use >>,
            e.g.: studies>>biotype;type
        :param individualFields: list of individual fields separated by semicolons, e.g.: studies;type. For nested fields use >>,
            e.g.: studies>>biotype;type
        :param familyFields: list of family fields separated by semicolons, e.g.: studies;type. For nested fields use >>,
            e.g.: studies>>biotype;type
        :param sampleFields: list of sample fields separated by semicolons, e.g.: studies;type. For nested fields use >>,
            e.g.: studies>>biotype;type
        :param cohortFields: list of cohort fields separated by semicolons, e.g.: studies;type. For nested fields use >>,
            e.g.: studies>>biotype;type
        """

        return self._get('aggregationStats', query_id=study, **options)

    def groups(self, studies, **options):
        """
        Return the groups present in the studies
        URL: /{apiVersion}/studies/{studies}/groups

        :param studies: Comma separated list of studies [[user@]project:]study where study and project can be either the id or alias up to
            a maximum of 100
        :param id: Group id. If provided, it will only fetch information for the provided group.
        :param name: group name. If provided, it will only fetch information for the provided group.
        :param silent: boolean to retrieve all possible entries that are queried for, false to raise an exception whenever one of the entries
            looked for cannot be shown for whichever reason
        """

        return self._get('groups', query_id=studies, **options)

    def scan_files(self, study, **options):
        """
        Scan the study folder to find untracked or missing files
        URL: /{apiVersion}/studies/{study}/scanFiles

        :param study: study id
        """

        return self._get('scanFiles', query_id=study, **options)

    def resync_files(self, study, **options):
        """
        Intended to keep the consistency between the database and the file system.
        Tracks new and/or removed files from a study. Files not available in the
        file system are tagged in their status as 'MISSING'
        URL: /{apiVersion}/studies/{study}/resyncFiles

        :param study: study id
        """

        return self._get('resyncFiles', query_id=study, **options)

    def search(self, **options):
        """
        Search studies
        URL: /{apiVersion}/studies/search

        :param project: project id
        :param name: study name
        :param id: study id
        :param alias: study alias
        :param fqn: study full qualified name
        :param type: type of study: CASE_CONTROL, CASE_SET...
        :param creationDate: creation date (Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805...)
        :param modificationDate: modification date (Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805...)
        :param status: status
        :param attributes: attributes
        :param nattributes: numerical attributes
        :param battributes: boolean attributes
        :param skipCount: skip count
        :param release: release value
        :param include: set which fields are included in the response, e.g.: name,alias...
        :param exclude: set which fields are excluded in the response, e.g.: name,alias...
        :param limit: max number of results to be returned.
        :param skip: number of results to be skipped.
        :param count: get a count of the number of results obtained. Deactivated by default.
        """

        return self._get('search', **options)

    def variable_sets(self, study, **options):
        """
        Fetch variableSets from a study
        URL: /{apiVersion}/studies/{study}/variableSets

        :param study: study id
        :param id: Id of the variableSet to be retrieved. If no id is passed, it will show all the variableSets of the study
        """

        return self._get('variableSets', query_id=study, **options)

    def permission_rules(self, study, entity, **options):
        """
        Fetch permission rules
        URL: /{apiVersion}/studies/{study}/permissionRules

        :param study: study id
        :param entity: entity where the permission rules should be applied to. Available entities ['SAMPLES','FILES','COHORTS',
            'INDIVIDUALS','FAMILIES','JOBS','CLINICAL_ANALYSES','PANELS']
        """

        options['entity'] = entity

        return self._get('permissionRules', query_id=study, **options)

    def create_groups(self, study, data, **options):
        """
        Create group [DEPRECATED]
        URL: /{apiVersion}/studies/{study}/groups/create

        :param study: study id
        :param data: dict with the following Model:

        {
            "id": "string",
            "name": "string",
            "users": "string"
        }
        """

        return self._post('groups', query_id=study, subcategory='create', data=data, **options)

    def update_groups(self, study, data, *options):
        """
        Add or remove a group
        URL: /{apiVersion}/studies/{study}/groups/update

        :param study: study id
        :param action: Action to be performed: ADD or REMOVE a group
        :param data: dict with the following Model:

        {
            "id": "string",
            "name": "string",
            "users": "string"
        }
        """

        return self._post('groups', query_id=study, subcategory='update', data=data, **options)


    def update_group(self, study, group, data, **options):
        """
        Add, set or remove users from an existing group
        URL: /{apiVersion}/studies/{study}/groups/{group}/users/update

        :param study: study id
        :param group: group id
        :param action: action ['ADD', 'SET', 'REMOVE']
        :param data: dict with the following Model:
        {
            "users": "string"
        }
        """

        return self._post('groups', query_id=study, subcategory='users/update', second_query_id=group, data=data, **options)

    def update_permission_rules(self, study, entity, data, **options):
        """
        Add or remove a permission rule
        URL: /{apiVersion}/studies/{study}/permissionRules/update

        :param study: study id
        :param entity: entity where the permission rules should be applied to. Available entities: ['SAMPLES','FILES','COHORTS',
            'INDIVIDUALS','FAMILIES','JOBS','CLINICAL_ANALYSES','PANELS']
        :param action: Action to be performed:
            - ADD (default) to add a new permission rule;
            - REMOVE to remove all permissions assigned by an existing permission rule
                (even if it overlaps any manual permission);
            - REVERT to remove all permissions assigned by an existing permission rule
                (keep manual overlaps);
            - NONE to remove an existing permission rule without removing any permissions
                that could have been assigned already by the permission rule.
        :param data: JSON containing the permission rule to be created or removed.

        data = {
          "id": "string",
          "query": {},
          "members": [
            "string"
          ],
          "permissions": [
            "string"
          ]
        }
        """

        options['entity'] = entity

        return self._post('permissionRules', query_id=study, subcategory='update', data=data, **options)

    def update_variable_sets(self, study, data, **options):
        """
        Add or remove a variableSet
        URL: /{apiVersion}/studies/{study}/variableSets/update

        :param study: study id
        :param action: Action to be performed ['ADD' default,'REMOVE']
        :param data: dict with the following Model:

        data = {
          "unique": true,
          "confidential": true,
          "id": "string",
          "name": "string",
          "description": "string",
          "variables": [
            {
              "id": "string",
              "name": "string",
              "category": "string",
              "type": "BOOLEAN",
              "defaultValue": {},
              "required": true,
              "multiValue": true,
              "allowedValues": [
                "string"
              ],
              "rank": 0,
              "dependsOn": "string",
              "description": "string",
              "variableSet": [
                {}
              ],
              "attributes": {}
            }
          ]
        }
        """

        return self._post('variableSets', query_id=study, data=data, subcategory="update", **options)

    def update_variable_set(self, study, variable_set, **options):
        """
        Add or remove variables to a VariableSet
        URL: /{apiVersion}/studies/{study}/variableSets/{variableSet}/variables/update

        :param study: study id
        :param variable_set: variable_set id
        :param action: Action to be performed ['ADD' default, 'REMOVE']
        :param data: dict with the following Model:

        data = {
          "id": "string",
          "name": "string",
          "category": "string",
          "type": "BOOLEAN",
          "defaultValue": {},
          "required": true,
          "multiValue": true,
          "allowedValues": [
            "string"
          ],
          "rank": 0,
          "dependsOn": "string",
          "description": "string",
          "variableSet": [
            {}
          ],
          "attributes": {}
        }
        """

        return self._post('variableSets', query_id=study, subcategory='variables/update', second_query_id=variable_set,
                          data=data, **options)
