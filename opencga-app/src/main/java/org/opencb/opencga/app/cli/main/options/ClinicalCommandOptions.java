package org.opencb.opencga.app.cli.main.options;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import org.opencb.opencga.app.cli.GeneralCliOptions;
import org.opencb.opencga.app.cli.main.options.commons.AclCommandOptions;

@Parameters(commandNames = {"clinical"}, commandDescription = "Clinical analysis commands")
public class ClinicalCommandOptions {

    public SearchCommandOptions searchCommandOptions;
    public InfoCommandOptions infoCommandOptions;
    public GroupByCommandOptions groupByCommandOptions;

    public AclCommandOptions.AclsCommandOptions aclsCommandOptions;
    public AclCommandOptions.AclsUpdateCommandOptions aclsUpdateCommandOptions;

    public JCommander jCommander;
    public GeneralCliOptions.CommonCommandOptions commonCommandOptions;
    public GeneralCliOptions.DataModelOptions commonDataModelOptions;
    public GeneralCliOptions.NumericOptions commonNumericOptions;

    public ClinicalCommandOptions(GeneralCliOptions.CommonCommandOptions commonCommandOptions,
                                  GeneralCliOptions.DataModelOptions dataModelOptions, GeneralCliOptions.NumericOptions numericOptions,
                                  JCommander jCommander) {

        this.commonCommandOptions = commonCommandOptions;
        this.commonDataModelOptions = dataModelOptions;
        this.commonNumericOptions = numericOptions;
        this.jCommander = jCommander;

        this.infoCommandOptions = new InfoCommandOptions();
        this.searchCommandOptions = new SearchCommandOptions();
        this.groupByCommandOptions = new GroupByCommandOptions();

        AclCommandOptions aclCommandOptions = new AclCommandOptions(commonCommandOptions);
        this.aclsCommandOptions = aclCommandOptions.getAclsCommandOptions();
        this.aclsUpdateCommandOptions = aclCommandOptions.getAclsUpdateCommandOptions();
    }


    public class BaseClinicalCommand extends GeneralCliOptions.StudyOption {

        @ParametersDelegate
        public GeneralCliOptions.CommonCommandOptions commonOptions = commonCommandOptions;

        @Parameter(names = {"--clinical"}, description = "Clinical analysis id", required = true, arity = 1)
        public String clinical;

    }

    @Parameters(commandNames = {"search"}, commandDescription = "Search clinical analyses")
    public class SearchCommandOptions extends GeneralCliOptions.StudyOption {

        @ParametersDelegate
        public GeneralCliOptions.CommonCommandOptions commonOptions = commonCommandOptions;

        @ParametersDelegate
        public GeneralCliOptions.DataModelOptions dataModelOptions = commonDataModelOptions;

        @ParametersDelegate
        public GeneralCliOptions.NumericOptions numericOptions = commonNumericOptions;

        @Parameter(names = {"--type"}, description = "Clinical analysis type.", arity = 1)
        public String type;

        @Parameter(names = {"--status"}, description = "Status.", arity = 1)
        public String status;

        @Parameter(names = {"--germline"}, description = "Germline file id", required = false, arity = 1)
        public String germline;

        @Parameter(names = {"--somatic"}, description = "Somatic file id", required = false, arity = 1)
        public String somatic;

        @Parameter(names = {"--family"}, description = "Family id", required = false, arity = 1)
        public String family;

        @Parameter(names = {"--subject"}, description = "Subject id of the clinical analysis", required = false, arity = 1)
        public String subject;

        @Parameter(names = {"--sample"}, description = "Sampe id", arity = 1)
        public String sample;

    }

    @Parameters(commandNames = {"info"}, commandDescription = "Get clinical analysis information")
    public class InfoCommandOptions extends BaseClinicalCommand {

        @ParametersDelegate
        public GeneralCliOptions.DataModelOptions dataModelOptions = commonDataModelOptions;

    }

    @Parameters(commandNames = {"group-by"}, commandDescription = "GroupBy clinical analysis")
    public class GroupByCommandOptions extends GeneralCliOptions.StudyOption {

        @ParametersDelegate
        public GeneralCliOptions.CommonCommandOptions commonOptions = commonCommandOptions;

        @Parameter(names = {"-f", "--fields"}, description = "Comma separated list of fields by which to group by.", required = true, arity = 1)
        public String fields;

        @Parameter(names = {"--type"}, description = "Clinical analysis type.", arity = 1)
        public String type;

        @Parameter(names = {"--status"}, description = "Status.", arity = 1)
        public String status;

        @Parameter(names = {"--germline"}, description = "Germline file id", required = false, arity = 1)
        public String germline;

        @Parameter(names = {"--somatic"}, description = "Somatic file id", required = false, arity = 1)
        public String somatic;

        @Parameter(names = {"--family"}, description = "Family id", required = false, arity = 1)
        public String family;

        @Parameter(names = {"--subject"}, description = "Subject id of the clinical analysis", required = false, arity = 1)
        public String subject;

        @Parameter(names = {"--sample"}, description = "Sampe id", arity = 1)
        public String sample;
    }

}
