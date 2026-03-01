from back.app.tools.image_tool import image_tool
from back.app.tools.userInfo_tool import userInfo_tool, userInfo_gpsToCity_tool

tools = [userInfo_tool, userInfo_gpsToCity_tool, image_tool]
tools_by_name = {tool.name: tool for tool in tools}