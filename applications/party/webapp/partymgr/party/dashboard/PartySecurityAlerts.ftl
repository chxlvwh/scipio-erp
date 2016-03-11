<#if securityAlerts?has_content>    
    <@section title=uiLabelMap.PartySecurityAlert>
        <@paginate mode="content" url=makeOfbizUrl("main") viewIndex=viewIndex!0 listSize=listSize!0 viewSize=viewSize!1>
            <@table type="data-list" role="grid" autoAltRows=true id="securityAlerts">
                <@thead>
                    <@tr valign="bottom" class="header-row">
                        <@th>${uiLabelMap.CommonReason}</@th>
                        <@th>${uiLabelMap.CommonFrom}</@th>
                        <@th>${uiLabelMap.CommonRequest}</@th>
                        <@th>${uiLabelMap.PartyClientIP}</@th>
                        <@th attribs={"aria-sort":"descending"}>${uiLabelMap.CommonDate}</@th>
                    </@tr>
                </@thead>
                <@tbody>
                    <#list securityAlerts as securityAlert>
                        <@tr>
                            <@td>
                                <#if securityAlert.enabled == 'N' && securityAlert.disabledDateTime?has_content>
                                    ${uiLabelMap.PartyAccountLocked}
                                <#elseif securityAlert.successfulLogin == 'N'>
                                    ${uiLabelMap.PartyLoginFailed}
                                <#else>
                                    ${uiLabelMap.PartyUnknown}
                                </#if> 
                            </@td>
                            
                            <@td>${securityAlert.userLoginId!}</@td>
                            <@td><a href="${securityAlert.requestUrl!}">${securityAlert.contentId?replace('.',' - ')!}</a></@td>
                            <@td>${securityAlert.serverIpAddress!}</@td>
                            <@td>${securityAlert.fromDate?string('yyyy-MM-dd HH:mm')!}</@td>
                        </@tr>
                    </#list>
                </@tbody>
            </@table>
            <script>
                $(document).ready(function() {        
                    var table = $('#securityAlerts').DataTable();
                    table.order( [ 5, 'desc' ] ).draw();
                } );
            </script>
        </@paginate>
    </@section>
<#else>
    <@commonMsg type="result-norecord"/>            
</#if>